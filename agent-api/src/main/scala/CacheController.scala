
/*
#
# Copyright 2013 The Trustees of Indiana University
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
*/


package htrc.agent

// actor that supports caching of job results; reads peristent cache index on
// startup, performs cache lookups and addition of cache entries on request,
// and performs periodic writes of the cache index

import akka.actor.{ Actor, Props }
import akka.util.Timeout
import akka.event.Logging
import scala.concurrent.duration._
import akka.pattern.ask
import akka.pattern.pipe

class CacheController extends Actor {

  // context import
  import context._

  // timeout for futures
  implicit val timeout = Timeout(30 seconds)

  // logging
  val log = Logging(context.system, this)

  val jobResultCache = new JobResultCache(HtrcConfig.cacheSize)
  var writeNecessary = false

  // schedule periodic writes of the cache index to disk
  val writeInterval = HtrcConfig.cacheWriteInterval
  log.debug("CACHE_CONTROLLER: cacheWriteInterval = {} seconds, " + 
            "initCacheSize = {}, maxCacheSize = {}", writeInterval,
            jobResultCache.size, HtrcConfig.cacheSize)

  val behavior: PartialFunction[Any,Unit] = {
    case m: CacheControllerMessage => 
      m match {
        case GetJobFromCache(js, token) =>
          log.debug("CACHE_CONTROLLER received GetJobFromCache({}, {})", js, 
                    token)

        case GetDataForJobRun(js, token) =>
          val currSender = sender
          log.debug("CACHE_CONTROLLER received GetDataForJobRun({}, {})", js, 
                    token)
          RegistryHttpClient.algorithmMetadata(js.algorithm, token) map {
            algMetadata =>
              JobResultCache.constructKey(js, algMetadata, token) map {
                jobKey => currSender ! DataForJobRun(jobKey, algMetadata)
              }
          }

	case WriteCacheToFile =>
          log.debug("CACHE_CONTROLLER received WriteCacheToFile")
	  if (writeNecessary) jobResultCache.writeCacheToFile
          system.scheduler.scheduleOnce(writeInterval seconds, self, 
                                        WriteCacheToFile)

      }
  }

  val unknown: PartialFunction[Any,Unit] = {
    case m =>
      log.error("Cache controller received unhandled message")
  }

  override def preStart(): Unit = {
    // send WriteCacheToFile msg to self after specified time interval to
    // ensure that the cache is periodically written to disk
    system.scheduler.scheduleOnce(writeInterval seconds, self, 
				  WriteCacheToFile)
  }

  def receive = behavior orElse unknown

}

// response for message GetDataForJobRun; algMetadata contains details needed
// to launch the job; the jobResultCacheKey is used if the results of the job
// after execution are to be cached
case class DataForJobRun(jobResultCacheKey: Option[String], 
                         algMetadata: JobProperties)
