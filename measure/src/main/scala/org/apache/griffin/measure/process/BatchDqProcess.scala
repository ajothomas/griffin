/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/
package org.apache.griffin.measure.process

import java.util.Date

import org.apache.griffin.measure.config.params._
import org.apache.griffin.measure.config.params.env._
import org.apache.griffin.measure.config.params.user._
import org.apache.griffin.measure.data.source.DataSourceFactory
import org.apache.griffin.measure.persist.{Persist, PersistFactory}
import org.apache.griffin.measure.process.engine.{DqEngineFactory, SparkSqlEngine}
import org.apache.griffin.measure.rule.adaptor.{RuleAdaptorGroup, RunPhase}
import org.apache.griffin.measure.rule.step.TimeInfo
import org.apache.griffin.measure.rule.udf.GriffinUdfs
import org.apache.griffin.measure.utils.JsonUtil
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.{SparkConf, SparkContext}

import scala.util.Try

case class BatchDqProcess(allParam: AllParam) extends DqProcess {

  val envParam: EnvParam = allParam.envParam
  val userParam: UserParam = allParam.userParam

  val sparkParam = envParam.sparkParam
  val metricName = userParam.name
  val dataSourceNames = userParam.dataSources.map(_.name)
  val baselineDsName = userParam.baselineDsName

  var sparkContext: SparkContext = _
  var sqlContext: SQLContext = _

  def retriable: Boolean = false

  def init: Try[_] = Try {
    val conf = new SparkConf().setAppName(metricName)
    conf.setAll(sparkParam.config)
    sparkContext = new SparkContext(conf)
    sparkContext.setLogLevel(sparkParam.logLevel)
    sqlContext = new HiveContext(sparkContext)

    // register udf
    GriffinUdfs.register(sqlContext)

    // init adaptors
    RuleAdaptorGroup.init(sqlContext, dataSourceNames, baselineDsName)
  }

  def run: Try[_] = Try {
    // start time
    val startTime = new Date().getTime

    val appTime = getAppTime

    // get persists to persist measure result
    val persistFactory = PersistFactory(envParam.persistParams, metricName)
    val persist: Persist = persistFactory.getPersists(appTime)

    // persist start id
    val applicationId = sparkContext.applicationId
    persist.start(applicationId)

    // get dq engines
    val dqEngines = DqEngineFactory.genDqEngines(sqlContext)

    // generate data sources
    val dataSources = DataSourceFactory.genDataSources(sqlContext, null, dqEngines, userParam.dataSources, metricName)
    dataSources.foreach(_.init)

    // init data sources
    val dsTmsts = dqEngines.loadData(dataSources, appTime)

    debug(s"data source timestamps: ${dsTmsts}")

    // generate rule steps
//    val ruleSteps = RuleAdaptorGroup.genConcreteRuleSteps(
//      TimeInfo(appTime, appTime), userParam.evaluateRuleParam, dsTmsts, BatchProcessType, RunPhase)
    val ruleSteps = RuleAdaptorGroup.genRuleSteps(
      TimeInfo(appTime, appTime), userParam.evaluateRuleParam, dsTmsts)

    // run rules
    dqEngines.runRuleSteps(ruleSteps)

    // persist results
    val timeGroups = dqEngines.persistAllMetrics(ruleSteps, persistFactory)

    val dfs = dqEngines.collectUpdateRDDs(ruleSteps, timeGroups.toSet)
    dfs.foreach(_._2.cache())

    dqEngines.persistAllRecords(dfs, persistFactory)
//    dqEngines.persistAllRecords(ruleSteps, persistFactory, timeGroups)

    dfs.foreach(_._2.unpersist())

    // end time
    val endTime = new Date().getTime
    persist.log(endTime, s"process using time: ${endTime - startTime} ms")

    // finish
    persist.finish()

//    sqlContext.tables().show(50)

    // clear temp table
//    ruleSteps.foreach { rs =>
//      println(rs)
//      //      sqlContext.dropTempTable(rs.ruleInfo.name)
//      sqlContext.dropTempTable(s"`${rs.ruleInfo.tmstName}`")
//      sqlContext.dropTempTable(s"`${rs.ruleInfo.tmstName}`")
//    }
//
//    // -- test --
//    sqlContext.tables().show(50)
  }

  def end: Try[_] = Try {
    sparkContext.stop
  }

//  private def cleanData(t: Long): Unit = {
//    try {
////      dataSources.foreach(_.cleanOldData)
////      dataSources.foreach(_.dropTable(t))
//
////      val cleanTime = TimeInfoCache.getCleanTime
////      CacheResultProcesser.refresh(cleanTime)
//
//      sqlContext.dropTempTable()
//    } catch {
//      case e: Throwable => error(s"clean data error: ${e.getMessage}")
//    }
//  }

}
