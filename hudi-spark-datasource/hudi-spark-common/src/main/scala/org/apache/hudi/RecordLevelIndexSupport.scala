/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi

import org.apache.hadoop.fs.FileStatus
import org.apache.hudi.client.common.HoodieSparkEngineContext
import org.apache.hudi.common.config.HoodieMetadataConfig
import org.apache.hudi.common.fs.FSUtils
import org.apache.hudi.common.model.HoodieRecord.HoodieMetadataField
import org.apache.hudi.common.table.HoodieTableMetaClient
import org.apache.hudi.metadata.{HoodieTableMetadata, HoodieTableMetadataUtil}
import org.apache.hudi.util.JFunction
import org.apache.spark.api.java.JavaSparkContext
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, EqualTo, Expression, In, Literal}

import scala.collection.{JavaConverters, mutable}

class RecordLevelIndexSupport(spark: SparkSession,
                              metadataConfig: HoodieMetadataConfig,
                              metaClient: HoodieTableMetaClient) {

  @transient private lazy val engineCtx = new HoodieSparkEngineContext(new JavaSparkContext(spark.sparkContext))
  @transient private lazy val metadataTable: HoodieTableMetadata =
    HoodieTableMetadata.create(engineCtx, metadataConfig, metaClient.getBasePathV2.toString)

  /**
   * Returns the list of candidate files which store the provided record keys based on Metadata Table Record Index.
   * @param allFiles - List of all files which needs to be considered for the query
   * @param recordKeys - List of record keys.
   * @return Sequence of file names which need to be queried
   */
  def getCandidateFiles(allFiles: Seq[FileStatus], recordKeys: List[String]): Set[String] = {
    val recordKeyLocationsMap = metadataTable.readRecordIndex(JavaConverters.seqAsJavaListConverter(recordKeys).asJava)
    val fileIdToPartitionMap: mutable.Map[String, String] = mutable.Map.empty
    val candidateFiles: mutable.Set[String] = mutable.Set.empty
    for (locations <- JavaConverters.collectionAsScalaIterableConverter(recordKeyLocationsMap.values()).asScala) {
      for (location <- JavaConverters.collectionAsScalaIterableConverter(locations).asScala) {
        fileIdToPartitionMap.put(location.getFileId, location.getPartitionPath)
      }
    }
    for (file <- allFiles) {
      val fileId = FSUtils.getFileIdFromFilePath(file.getPath)
      val partitionOpt = fileIdToPartitionMap.get(fileId)
      if (partitionOpt.isDefined) {
        candidateFiles += file.getPath.getName
      }
    }
    candidateFiles.toSet
  }

  /**
   * Returns the list of candidate files which store the provided record keys based on Metadata Table Secondary Index
   * and Metadata Table Record Index.
   * @param secondaryKeys - List of secondary keys.
   * @return Sequence of file names which need to be queried
   */
  def getCandidateFilesFromSecondaryIndex(allFiles: Seq[FileStatus], secondaryKeys: List[String]): Set[String] = {
    val recordKeyLocationsMap = metadataTable.readSecondaryIndex(JavaConverters.seqAsJavaListConverter(secondaryKeys).asJava)
    val fileIdToPartitionMap: mutable.Map[String, String] = mutable.Map.empty
    val candidateFiles: mutable.Set[String] = mutable.Set.empty
    for (locations <- JavaConverters.collectionAsScalaIterableConverter(recordKeyLocationsMap.values()).asScala) {
      for (location <- JavaConverters.collectionAsScalaIterableConverter(locations).asScala) {
        fileIdToPartitionMap.put(location.getFileId, location.getPartitionPath)
      }
    }

    for (file <- allFiles) {
      val fileId = FSUtils.getFileIdFromFilePath(file.getPath)
      val partitionOpt = fileIdToPartitionMap.get(fileId)
      if (partitionOpt.isDefined) {
        candidateFiles += file.getPath.getName
      }
    }
    candidateFiles.toSet
  }

  /**
   * Returns the configured record key for the table if it is a simple record key else returns empty option.
   */
  private def getRecordKeyConfig: Option[String] = {
    val recordKeysOpt: org.apache.hudi.common.util.Option[Array[String]] = metaClient.getTableConfig.getRecordKeyFields
    val recordKeyOpt = recordKeysOpt.map[String](JFunction.toJavaFunction[Array[String], String](arr =>
      if (arr.length == 1) {
        arr(0)
      } else {
        null
      }))
    Option.apply(recordKeyOpt.orElse(null))
  }

  /**
   * Returns the configured secondary key for the table
   * TODO: Handle multiple secondary indexes (similar to functional index)
   */
  private def getSecondaryKeyConfig: Option[String] = {
    val secondaryKeysOpt: org.apache.hudi.common.util.Option[Array[String]] = metaClient.getTableConfig.getSecondaryKeyFields
    val secondaryKeyOpt = secondaryKeysOpt.map[String](JFunction.toJavaFunction[Array[String], String](arr =>
      if (arr.length == 1) {
        arr(0)
      } else {
        null
      }))
    Option.apply(secondaryKeyOpt.orElse(null))
  }

  /**
   * Matches the configured  key (record key or secondary key) with the input attribute name.
   * @param attributeName The attribute name provided in the query
   * @param isPrimaryKey Should match primary key (record key). If false, it matches secondary key.
   * @return true if input attribute name matches the configured key
   * TODO: Handle multiple secondary indexes (similar to functional index)
   */
  private def attributeMatchesKey(attributeName: String, isPrimaryKey: Boolean): Boolean = {
    val keyOpt = if (isPrimaryKey) getRecordKeyConfig else getSecondaryKeyConfig
    if (keyOpt.isDefined && keyOpt.get == attributeName) {
      true
    } else {
      false
    }
  }

  /**
   * Returns the attribute and literal pair given the operands of a binary operator. The pair is returned only if one of
   * the operand is an attribute and other is literal. In other cases it returns an empty Option.
   * @param expression1 - Left operand of the binary operator
   * @param expression2 - Right operand of the binary operator
   * @return Attribute and literal pair
   */
  private def getAttributeLiteralTuple(expression1: Expression, expression2: Expression): Option[(AttributeReference, Literal)] = {
    expression1 match {
      case attr: AttributeReference => expression2 match {
        case literal: Literal =>
          Option.apply(attr, literal)
        case _ =>
          Option.empty
      }
      case literal: Literal => expression2 match {
        case attr: AttributeReference =>
          Option.apply(attr, literal)
        case _ =>
          Option.empty
      }
      case _ => Option.empty
    }

  }

  /**
   * Given query filters, it filters the EqualTo and IN queries on simple record key columns and returns a tuple of
   * list of such queries and list of record key literals present in the query.
   * If record index is not available, it returns empty list for record filters and record keys
   * @param queryFilters The queries that need to be filtered.
   * @return Tuple of List of filtered queries and list of record key literals that need to be matched
   */
  def filterQueriesWithRecordKey(queryFilters: Seq[Expression]): (List[Expression], List[String]) = {
    if (!isIndexAvailable) {
      (List.empty, List.empty)
    } else {
      var recordKeyQueries: List[Expression] = List.empty
      var recordKeys: List[String] = List.empty
      for (query <- queryFilters) {
        filterQueryWithKey(query, true).foreach({
          case (exp: Expression, recKeys: List[String]) =>
            recordKeys = recordKeys ++ recKeys
            recordKeyQueries = recordKeyQueries :+ exp
        })
      }

      Tuple2.apply(recordKeyQueries, recordKeys)
    }
  }

  def filterQueriesWithSecondaryKey(queryFilters: Seq[Expression]): (List[Expression], List[String]) = {
    if (!isSecondaryIndexAvailable) {
      (List.empty, List.empty)
    } else {
      var secondaryKeyQueries: List[Expression] = List.empty
      var secondaryKeys: List[String] = List.empty
      for (query <- queryFilters) {
        filterQueryWithKey(query, false).foreach({
          case (exp: Expression, recKeys: List[String]) =>
            secondaryKeys = secondaryKeys ++ recKeys
            secondaryKeyQueries = secondaryKeyQueries :+ exp
        })
      }

      Tuple2.apply(secondaryKeyQueries, secondaryKeys)
    }
  }

  /**
   * If the input query is an EqualTo or IN query on simple record key columns, the function returns a tuple of
   * list of the query and list of record key literals present in the query otherwise returns an empty option.
   *
   * @param queryFilter The query that need to be filtered.
   * @param isPrimaryKey Should use primary key (record key) to filter. If false, it uses secondary key.
   * @return Tuple of filtered query and list of record key literals that need to be matched
   */
  private def filterQueryWithKey(queryFilter: Expression, isPrimaryKey: Boolean): Option[(Expression, List[String])] = {
    queryFilter match {
      case equalToQuery: EqualTo =>
        val (attribute, literal) = getAttributeLiteralTuple(equalToQuery.left, equalToQuery.right).orNull
        if (attribute != null && attribute.name != null && attributeMatchesKey(attribute.name, isPrimaryKey)) {
          Option.apply(equalToQuery, List.apply(literal.value.toString))
        } else {
          Option.empty
        }
      case inQuery: In =>
        var validINQuery = true
        inQuery.value match {
          case _: AttributeReference =>
          case _ => validINQuery = false
        }

        var literals: List[String] = List.empty
        inQuery.list.foreach {
          case literal: Literal => literals = literals :+ literal.value.toString
          case _ => validINQuery = false
        }
        if (validINQuery) {
          Option.apply(inQuery, literals)
        } else {
          Option.empty
        }
      case _ => Option.empty
    }
  }

  /**
   * Return true if metadata table is enabled and record index metadata partition is available.
   */
  def isIndexAvailable: Boolean = {
    metadataConfig.enabled && metaClient.getTableConfig.getMetadataPartitions.contains(HoodieTableMetadataUtil.PARTITION_NAME_RECORD_INDEX)
  }

  /**
   * Return true if metadata table is enabled and secondary index metadata partition is available.
   */
  def isSecondaryIndexAvailable: Boolean = {
    isIndexAvailable && metaClient.getTableConfig.getMetadataPartitions.contains(HoodieTableMetadataUtil.PARTITION_NAME_SECONDARY_INDEX_PREFIX)
  }
}
