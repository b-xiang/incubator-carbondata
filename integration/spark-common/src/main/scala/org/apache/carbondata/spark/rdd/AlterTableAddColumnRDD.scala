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

package org.apache.carbondata.spark.rdd

import org.apache.spark.{Partition, SparkContext, TaskContext}
import org.apache.spark.rdd.RDD

import org.apache.carbondata.common.logging.LogServiceFactory
import org.apache.carbondata.core.constants.CarbonCommonConstants
import org.apache.carbondata.core.metadata.CarbonTableIdentifier
import org.apache.carbondata.core.metadata.encoder.Encoding
import org.apache.carbondata.core.metadata.schema.table.column.ColumnSchema
import org.apache.carbondata.core.util.path.CarbonStorePath
import org.apache.carbondata.spark.util.GlobalDictionaryUtil

/**
 * This is a partitioner class for dividing the newly added columns into partitions
 *
 * @param rddId
 * @param idx
 * @param schema
 */
class AddColumnPartition(rddId: Int, idx: Int, schema: ColumnSchema) extends Partition {
  override def index: Int = idx

  override def hashCode(): Int = 41 * (41 + rddId) + idx

  val columnSchema = schema
}

/**
 * This class is aimed at generating dictionary file for the newly added columns
 */
class AlterTableAddColumnRDD[K, V](sc: SparkContext,
    @transient newColumns: Seq[ColumnSchema],
    carbonTableIdentifier: CarbonTableIdentifier,
    carbonStorePath: String) extends RDD[(Int, String)](sc, Nil) {

  override def getPartitions: Array[Partition] = {
    newColumns.zipWithIndex.map { column =>
      new AddColumnPartition(id, column._2, column._1)
    }.toArray
  }

  override def compute(split: Partition,
      context: TaskContext): Iterator[(Int, String)] = {
    val LOGGER = LogServiceFactory.getLogService(this.getClass.getName)
    val status = CarbonCommonConstants.STORE_LOADSTATUS_SUCCESS
    val iter = new Iterator[(Int, String)] {
      try {
        val columnSchema = split.asInstanceOf[AddColumnPartition].columnSchema
        // create dictionary file if it is a dictionary column
        if (columnSchema.hasEncoding(Encoding.DICTIONARY) &&
            !columnSchema.hasEncoding(Encoding.DIRECT_DICTIONARY)) {
          val carbonTablePath = CarbonStorePath
            .getCarbonTablePath(carbonStorePath, carbonTableIdentifier)
          var rawData: String = null
          if (null != columnSchema.getDefaultValue) {
            rawData = new String(columnSchema.getDefaultValue,
              CarbonCommonConstants.DEFAULT_CHARSET_CLASS)
          }
          GlobalDictionaryUtil
            .loadDefaultDictionaryValueForNewColumn(carbonTablePath,
              columnSchema,
              carbonTableIdentifier,
              carbonStorePath,
              rawData)
        }
      } catch {
        case ex: Exception =>
          throw ex
      }

      var finished = false

      override def hasNext: Boolean = {

        if (!finished) {
          finished = true
          finished
        } else {
          !finished
        }
      }

      override def next(): (Int, String) = {
        (split.index, status)
      }
    }
    iter
  }

}
