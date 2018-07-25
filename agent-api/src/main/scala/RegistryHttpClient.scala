
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

// An example actor that uses the Spray asynchronous http client

import scala.concurrent.Future
import akka.actor._
import spray.io._
import spray.util._
import spray.http._
import spray.client.pipelining._
import HttpMethods._
import scala.concurrent.duration._
import akka.util.Timeout
import akka.actor.{ ActorSystem, Props, Actor }
import akka.pattern.ask
import akka.event.Logging
import akka.event.slf4j.Logger
import scala.util.{Success, Failure}
import scala.xml._
import HtrcConfig._
import MediaTypes._
import java.io.File
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import com.github.tototoshi.csv._
import HttpHeaders._

object RegistryHttpClient extends HtrcHttpClient {

  implicit val regTimeout = Timeout(5 seconds)
  import system.dispatcher
  override val log = Logging(system, "registry-http-client")
  val auditLog = Logger("audit")

  def queryRegistry(query: String, method: HttpMethod, token: String,
    acceptContentType: String, body: Option[NodeSeq] = None,
    optContentType: Option[MediaType] = None): Future[HttpResponse] = {

    val uri = registryUrl + query

    // val contentType = `application/xml`
    val contentType = optContentType.getOrElse(`application/xml`)

    val headers = List(RawHeader("Accept", acceptContentType),
      RawHeader("Authorization", "Bearer " + token))

    val httpRequest =
      body map { b =>
        HttpRequest(method = method, uri = uri,
          entity = HttpEntity(contentType, b.toString), headers = headers)
      } getOrElse {
        HttpRequest(method = method, uri = uri, headers = headers)
      }

    val response = queryService(uri, httpRequest)
    // log.debug("REGISTRY_CLIENT_QUERY\tTOKEN: {}\tQUERY: {}", token, query)
    log.debug("REGISTRY_CLIENT_QUERY\tQUERY: {}", query)

    // printResponse(response)
    response
  }

  // all methods except for collectionData require content type of the
  // response to be "application/xml"
  def query(queryStr: String, method: HttpMethod, token: String,
    body: Option[NodeSeq] = None, optContentType: Option[MediaType] = None): Future[HttpResponse] = {
    queryRegistry(queryStr, method, token, "application/xml", body=body,
      optContentType=optContentType)
  }

  // method that may be used to send queries to different services for the
  // purpose of testing
  def testQuery(serviceUrl: String, query: String, method: HttpMethod, 
                token: String, acceptContentType: String,
		body: Option[NodeSeq] = None): Future[HttpResponse] = {

    val uri = serviceUrl + query

    val headers = List(RawHeader("Accept", acceptContentType),
      RawHeader("Authorization", "Bearer " + token))

    val contentType = `application/json`

    val httpRequest =
      body map { b =>
        HttpRequest(method = method, uri = query,
          entity = HttpEntity(contentType, b.toString), headers = headers)
      } getOrElse {
        HttpRequest(method = method, uri = query, headers = headers)
      }

    val response = queryService(uri, httpRequest)
    log.debug("TEST_QUERY\tQUERY: {}", query)

    printResponse(response)

    response
  }

  // these two functions primarily for debugging

  def printResponse(response: Future[HttpResponse]) {
    response onComplete {
      case Success(response) =>
        log.info(
          """|Response for GET request to random registry url:
          |status : {}
          |headers: {}
          |body   : {}""".stripMargin,
          response.status.value, response.headers.mkString("\n  ", "\n  ", ""), 
          response.entity.asString)
      case Failure(error) =>
        log.error(error.toString)
    }
  }

  def queryAndPrint(str: String, method: HttpMethod, token: String) {
    printResponse(query(str, method, token))
  }

  // Specific Agent Queries
  
  def collectionData(rawName: String, inputs: JobInputs, dest: String): Future[Boolean] = {
    // audit log analyzer output
    // type collection_name request_id ip token job_id job_name algorithm
    val fstr = "REGISTRY_FETCH_COLLECTION\t%s\t%s\t%s\t%s\t%s\t%s".format(
             rawName, inputs.requestId, inputs.ip, inputs.token,
             inputs.name, inputs.algorithm)
    auditLog.info(fstr)

    val name = rawName.split('@')(0)
    val author = rawName.split('@')(1)

    // url encode the workset name; space is encoded as '+', but should be "%20"
    // when part of the url
    val encName = java.net.URLEncoder.encode(name, "utf-8").replace("+", "%20")

    val q =
      if (HtrcConfig.requiresWorksetWithHeader(inputs))
      // use new REST call to deal with worksets that may contain class
      // labels and other metadata apart from volume ids, and returns a list
      // with a "volume_id, class, ..." header
      queryRegistry("worksets/"+encName+"/volumes?author="+author, GET, 
        inputs.token, "text/csv")
      // otherwise, use old REST call to get just a list of volume ids,
      // without any header row; required for Marc_Downloader,
      // Simple_Deployable_Word_count
      else queryRegistry("worksets/"+encName+"/volumes.txt?author="+author, GET, 
                         inputs.token, "application/xml")

    q map { response =>
      if (response.status.isSuccess) {
        writeFileWithEmptyFieldSubst(response.entity.data.toByteArray, dest)
        // true
      }
      else false
    }
  }

  def algorithmMetadata(name: String, token: String): Future[JobProperties] = {
//   val fstr = "REGISTRY_FETCH_FILE\t%s\t%s\t%s\t%s\t%s\t%s".format(
//             name, inputs.requestId, inputs.ip, inputs.token,
//             inputs.name, inputs.algorithm)
//    log.info(fstr)
    val q = query("files/algorithmfolder/"+name+".xml?public=true", GET, token)
    q map { response =>
      JobProperties(XML.loadString(response.entity.asString)) 
    }
  }

  // obtain the time of last modification of the specified algorithm XML file
  def algorithmXMLTimestamp(name: String, token: String): Future[Option[String]] = {
    val q = query("files/algorithmfolder/"+name+".xml?public=true", OPTIONS, 
                  token)
    q map { response =>
      if (response.status.isSuccess) {
        val algXMLMetadata = XML.loadString(response.entity.asString)
        Some(algXMLMetadata \ "lastModified" text)
      } else {
        log.error("REGISTRY_CLIENT_QUERY: {} error in obtaining " + 
                  "algorithmXMLTimestamp({}, {})", response.status.value, name, 
                  token)
        None
      }
    }
    // Future { Some("algXMLTimestamp") }
  }

  // the Future result contains an Option containing a pair, Some((timestamp,
  // isPublic)), where timestamp is the time of last modification of the
  // given collection, and isPublic is a Boolean value which is true if the
  // collection is public, false otherwise; if the registry extension call to
  // obtain the collection metadata fails, then return Future(None); it is
  // expected that if the collection is private to a user v different from
  // the user u that owns the given oauth token, then the method returns
  // Future(None)
  def collectionMetadata(name: String, token: String): Future[Option[(String, Boolean)]] = {
    val title = name.split('@')(0)
    val author = name.split('@')(1)

    // url encode the workset title; space is encoded as '+', but should be "%20"
    // when part of the url
    val encTitle = java.net.URLEncoder.encode(title, "utf-8").replace("+", "%20")

    val worksetQuery="worksets/"+encTitle+"/metadata?author="+author
    val q = query(worksetQuery, GET, token)
    q map { response =>
      if (response.status.isSuccess) {
        val metadata = XML.loadString(response.entity.asString)
        // val res = (metadata \\ "lastModified" text)
        // log.debug("REGISTRY_CLIENT_QUERY: collectionMetadata({}) metadata = " +
        //           "{}, result = {}", name, metadata, res)
        Some((metadata \\ "lastModified" text),
             (metadata \\ "public" text).toBoolean)
      } else {
        log.error("REGISTRY_CLIENT_QUERY: {} error in obtaining " + 
                  "collectionMetadata({}, {})", response.status.value, name, 
                  token)
        None
      }
    }
  }

  def worksetMetadata(name: String, token: String): Future[Option[WorksetMetadata]] = {
    val title = name.split('@')(0)
    val author = name.split('@')(1)

    // url encode the workset title; space is encoded as '+', but should be
    // "%20" when part of the url
    val encTitle = java.net.URLEncoder.encode(title, "utf-8").replace("+", "%20")

    val worksetQuery="worksets/"+encTitle+"/metadata?author="+author
    val q = query(worksetQuery, GET, token)
    q map { response =>
      if (response.status.isSuccess) {
        val metadata = XML.loadString(response.entity.asString)
        Some(WorksetMetadata(metadata))
      } else {
        log.error("REGISTRY_CLIENT_QUERY: {} error in obtaining " +
          "worksetMetadata({}, {})", response.status.value, name,
          token)
        None
      }
    }
  }

  def fileDownload(name: String, inputs: JobInputs, dest: String): Future[Boolean] = {
    
    // audit log analyzer output
    // type collection_name request_id ip token job_id job_name algorithm
    val fstr = "REGISTRY_FETCH_FILE\t%s\t%s\t%s\t%s\t%s\t%s".format(
             name, inputs.requestId, inputs.ip, inputs.token,
             inputs.name, inputs.algorithm)
    auditLog.info(fstr)

    val q = query("files/"+name+"?public=true", GET, inputs.token)
    q map { response =>
      if (response.status.isSuccess) {
        val bytes = response.entity.data.toByteArray
        writeFile(bytes, dest) 
        true
      }
      else false
    }
  }

  // Saving and loading saved job information
  
  val savedJobLocation = HtrcConfig.savedJobLocation

  def listSavedJobs(token: String): Future[List[String]] = {
    val q = query("files/"+savedJobLocation, OPTIONS, token)
    q map { response =>
      val raw = XML.loadString(response.entity.asString)
      (raw \ "entries" \ "entry") filter { entry =>
        ((entry \ "contentType").text != "collection")
      } map { entry => 
        (entry \ "name").text
      } toList
    }
  }    
    
  def downloadSavedJobs(names: List[String], token: String): Future[List[SavedHtrcJob]] = {
    val qs = names map { n => query("files/"+savedJobLocation+"/"+n, GET, token) }

    val fqs = Future.sequence(qs.toList)
    val res = fqs map { li => li.map { response =>
      try {
        val raw = XML.loadString(response.entity.asString)
        SavedHtrcJob(raw)
      } catch {
        // ignore files in files/<savedJobLocation> that are not in the 
        // expected format
        case e: Exception => 
          log.debug("REGISTRY_DOWNLOAD_SAVED_JOBS warning: unexpected error " +
                    "in reading file(s) in files/" + savedJobLocation + 
                    ". Exception " + e)
          null
      } 
    }}
    // remove null values produced by files that are not in the expected format 
    res map { ls => ls.filter(_ != null) }
  }

  def saveJob(status: JobComplete, id: String, 
              token: String): Future[Boolean] = {
    // use the new registry extension API that requires the username
    val saveJobQuery = "files/" + savedJobLocation + "/" + id + 
                       "?user=" + status.submitter
    val q = query(saveJobQuery, PUT, token, Some(status.saveXml))
    q map { response => 
      if (response.status.isSuccess) 
        true 
      else {
        log.debug("REGISTRY_CLIENT_SAVE_JOB_ERROR\tQUERY: {}\tSTATUS: {}", 
                  saveJobQuery, response.status)
        false
      }
    }
  }

  def deleteJob(id: String, token: String): Future[Boolean] = {
    val q = query("files/"+savedJobLocation+"/"+id, DELETE, token)
    q map { response => true }
  }
     
  def now[T](f: Future[T]): T = scala.concurrent.Await.result(f, 5 seconds)

  def writeFile(bytes: Array[Byte], dest: String) {
    val out = new java.io.FileOutputStream(dest)
    try {
      out.write(bytes)
    } finally {
      out.close()
    }
  }

  // csv data for worksets may contain empty fields; these are not handled by
  // Meandre; replace empty fields with a dummy value; call writeFile if
  // substitution of empty fields is not required
  def writeFileWithEmptyFieldSubst(bytes: Array[Byte], dest: String): Boolean = {
    val byteRdr = new InputStreamReader(new ByteArrayInputStream(bytes))
    val reader = CSVReader.open(byteRdr)

    var res = true
    try {
      val writer = CSVWriter.open(new File(dest))

      try {
        reader foreach { row =>
          writer.writeRow(row.map(x => (if (x == "") "HTRC_DUMMY_VAL" else x)))
        }
      } catch {
        case e: Exception => 
          log.error("REGISTRY_CLIENT_QUERY: exception in writing to {}: {}",
            dest, e)
          res = false
      } finally {
        writer.close()
      }
    } catch {
      case e: Exception =>
        log.error("REGISTRY_CLIENT_QUERY: exception in writing to {}: {}",
          dest, e)
        res = false
    } finally {
      byteRdr.close()
      reader.close()
    }
    res
  }

}
