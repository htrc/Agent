package htrcagent

/*
 * This actor is the sole point of access to the registry.
 * ALL registry requests go through here.
 * 
 */

import akka.actor.Actor
import akka.actor.Actor._
import javax.ws.rs.{GET, Path, Produces}
import akka.actor.Actor.registry._
import akka.routing.Routing.Broadcast
import akka.routing.Routing
import akka.routing.CyclicIterator
import java.security.cert.X509Certificate
import java.security.PrivateKey
import scala.collection.mutable.HashMap
import akka.actor.ActorRef
import javax.ws.rs.PathParam
import java.util.UUID
import edu.indiana.d2i.registry._
import java.net.URI
import java.util.Date
import org.slf4j.{Logger,LoggerFactory}
import java.util.Properties
import java.io.{File, BufferedReader, InputStreamReader, FileOutputStream}
import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import java.io.FileInputStream
import java.io.FileWriter
import java.io.BufferedWriter
import java.util.ArrayList
import org.apache.commons.io.FileUtils
import java.lang.ProcessBuilder
import java.lang.Process
import javax.ws.rs.PUT
import javax.ws.rs.Consumes
import scala.xml.NodeSeq
import javax.xml.bind.annotation.XmlRootElement
import java.io.InputStream
import scala.xml.Node
import javax.xml.bind.JAXBElement
import scala.xml.XML
import org.apache.commons.codec.binary.Hex
import java.io.FileReader
import scala.xml._

// Messages the RegistryActor responds to
  
case class GetAlgorithmExecutable(algoName: String, workingDir: String) // Option[String]
  
case class WriteVolumesTextFile (volumes:List[String],workingDir: String) // String
  
case object RegistryListCollections // scala.xml.Elem
  
case object RegistryListAvailableAlgorithms // scala.xml.Elem
  
//case class GetCollectionVolumeIDs(collectionName:String) // List[String]

// storing URNs for users doesn't work; 
// the next two methods turn them into something the registry API can deal with
case class EncodeUserURNForRegistry(userURN:String) // String 
	
case class DecodeUserURNFromRegistry(encodedUserString:String) // String

case class PostResultsToRegistry(userURN:String, 
      algorithmID:String,
      resultNameAndValueTuples:List[AlgorithmResult]) 
              // List[String] // list of registry resource paths

class RegistryActor extends Actor with Loggable {

  val registryClientInitializer = (()=>new RegistryClient)
  var registryClient = registryClientInitializer()
  
  def restartRegistryClient(): Boolean = {
    try {
    	registryClient = registryClientInitializer()
    	true
    } catch {
      case e => {
        logger.warn("====> Registry restart failed")
        throw e
      }
    }
  }
  
  def receive = {
    
    case GetAlgorithmExecutable(algoName, workingDir) =>
      
      spawn {
        self reply (getAlgorithmExecutable(algoName, workingDir))
      }
      
    case WriteVolumesTextFile(volumes, workingDir) =>
      
      spawn {
        self reply writeVolumesTextFile(volumes, workingDir)
      }
      
    case RegistryListCollections =>
      
      spawn {
        self reply availableCollectionList
      }
      
    case RegistryListAvailableAlgorithms => 
      
      spawn {
        self reply availableAlgorithmList
      }
      
    case GetCollectionVolumeIDs(collectionName) =>
      
      spawn {
        logger.debug("!!!!> inside getCollectionVolumeIDs, asked for "+collectionName)
    	val volumeIDs:java.util.List[String] = registryClient.getVolumeIDsFromCollection(collectionName)
    	logger.debug("!!!!> got the list of volumes, size is"+volumeIDs.toList.length)
	  	
    	self reply volumeIDs.toList
      }
	  	
    case EncodeUserURNForRegistry(userURN) =>

      spawn {
        self reply new String(Hex.encodeHex(userURN.getBytes()))
      }
      
    case DecodeUserURNFromRegistry(encodedUserString) =>
      
      spawn {
        logger.warn("!!!!> Need to test decodeUserURNFromRegistry")
        self reply Hex.decodeHex(encodedUserString.toCharArray()).toString
      }
      
    case PostResultsToRegistry(userURN, algorithmID, resultNameAndValueTuples) =>
      
      spawn {
        self reply postResultsToRegistry(userURN, algorithmID, resultNameAndValueTuples)
      }
      
  }
  
  // A wrapper function to provide registry exception handling
  // Provides features like client restart
  def registryExceptionHandler[T](func: () => T, retries: Int = 0): T = {
    
    try {
      func()
    } catch {
      case npe: NullPointerException => {
        logger.warn("====> RegistryClient threw a NPE, recovering...")
        restartRegistryClient()
        if(retries < 10)
        	registryExceptionHandler(func, retries+1)
        else
          throw npe
      }
    }
    
  }
  
  def fetchAlgorithm(algoName: String, workingDir: String): Option[String] = {
    
    val algorithm = registryClient.getScriptFile(algoName)
    
    if (algorithm == null) {
      logger.warn("====> Couldn't find algorithm executable in registry, for "+algoName)
      None
    } else {
      logger.debug("====> Successfully found algorithm executable in registry: "+algorithm)     
      Some(algorithm)
    }
    
  }
  
  // Currently uses props file and not the registry
  def availableAlgorithmList: xml.Elem = {
    
    val algorithms = RuntimeProperties("hardcodedalgorithmsavailable").toString.split(",").toList
    
    <availableAlgorithms>
      {for (a <- algorithms) yield <algorithm>{Text(a)}</algorithm>}
    </availableAlgorithms>
    
  }

    // Currently uses props file and not the registry
  def availableCollectionList: xml.Elem = {
    
    val collections = RuntimeProperties("hardcodedcollectionnames").toString.split(",").toList

    <collections>
      {for (c <- collections) yield <collection>{Text(c)}</collection>}
    </collections>
    
  }
  
  // Writes a newline-delimited file of volume names into working dir
  // returns the file name
  def writeVolumesTextFile (volumes:List[String], workingDir: String):String = {
    
    val volumeTextFileName = "vols-" + UUID.randomUUID.toString + ".txt"
    val outstream = new FileWriter(workingDir + File.separator + volumeTextFileName)
    val out = new BufferedWriter(outstream)
    
    volumes.foreach((v => out.write(v+"\n")))
    out.close()
    
    volumeTextFileName
    
  }
  
  // copy-pasta code. Fix it!
    def postResultsToRegistry(userURN:String, 
      algorithmID:String,
      theResultList:List[AlgorithmResult]) = {
      // need to convert the user URN to an acceptable hex string
      // so that registry doesn't choke
      val userNameAsHex = encodeUserURNForRegistry(userURN)
      println("===> Results to post: "+theResultList.toString)
      val registrySuccessPathList = for (result <- theResultList) yield ({ 
        
        logger.warn("!!!!> We stream in huge files when posting them to the registry")
        logger.warn("!!!!> This needs to be corrected..")
        // Yes, we will stream the entire thing in and post it.  This 
        // is a horrible idea, and needs to be corrected.
        val (resultID,resultAsString) = result match {
          case StdoutResult(outstring) => {
            ("stdout",outstring)
          }
          case StderrResult(outstring) => {
            ("stderr",outstring)
          }
          case FileResult(workingDir,fileName) => {
            val buf = (new BufferedReader(new FileReader(workingDir+File.separator+fileName)))
            val strBuf = new StringBuffer
            while (buf.ready) {
              strBuf.append(buf.readLine())
            }
            buf.close()
            ("file/"+fileName,strBuf.toString)
            
          }
        }
        val resourcePath = this.registryResultPathPrefix + "/"+userNameAsHex+"/"+algorithmID+"/"+resultID
        logger.info("====> Posting a result for user "+userURN+" to this registry resource path : "+resourcePath)
        val metadataToPost = "<result><userURN>"+userURN+"</userURN></result>"
        val resultPath= { 
            def tryIt() = { 
              try {
                registryClient.postResourse(   
                		
                		resourcePath,
                		resultAsString,
                		metadataToPost
                		) }
              catch {
                case e => { logger.error(e.getStackTrace().toString) ; null }
              }
            }
            
            // need to retry this a few times, potentially
            var count = 0
            var temp:String = null
            while (temp == null && count < 10) {
              temp = tryIt()
              count = count + 1
            }
            if (temp == null) {
              throw new RuntimeException("couldn't post resource to registry: "+(resourcePath,resultAsString,metadataToPost))
            } else {
              temp
            }
        }
        
        // used this before i had retries
//        val resultPath = registryClient.postResourse(   
//                		
//                		resourcePath,
//                		resultAsString,
//                		metadataToPost
//                		) 
//        println("====> registry posting value is :"+resultPath)
//        
        
        resultPath
      }) 
      
	  // for now we return none , until we can get a return val out of the above postResource call
	  registrySuccessPathList
	  // above could have null vals!
	  
	    
  }
    
  def encodeUserURNForRegistry(userURN:String) : String = {
    // for now we use a Hex encoding to do this.  Registry API is extremely choosy about
    // what characters we use ... = % + etc are all off limits
    
    // it's an array of characters... you need to use the following "new String"
    // way of doing things instead of xyz.toString
    new String(Hex.encodeHex(userURN.getBytes()))
  }
  
  def decodeUserURNFromRegistry(userIDInRegistry:String) : String = {
    logger.warn("!!!!> Need to test decodeUserURNFromRegistry")
    Hex.decodeHex(userIDInRegistry.toCharArray()).toString
  }
  
  private def registryResultPathPrefix = "/results"
    
  val copyAlgoJarToWorkingDir = true
    
  def getAlgorithmExecutable(algoName: String, workingDir: String): Option[String] = {
    
	    def getAlgoFromRegistryHelper = {
	    // this method should contact the registry and return a file(executable)
	      
		    val jarFile:String = try {
		      registryClient.getScriptFile(algoName)
		     
		    } catch {
		      case npe: NullPointerException => {
		        logger.warn("====> downloadFile in RegistryClient threw an NPE, recovering...")
		        null
		      }
		      case e => throw e
		    }
		    
		    if (jarFile == null) {
		      logger.warn("====> Couldn't find algorithm executable in registry, for "+algoName)
		      None
		    } else {
		      logger.debug("====> Successfully found algorithm executable in registry: "+jarFile)
		      //val f = new File(workingDir + File.separator + jarFile)
		      //val f = new File(jarFile)
		      
		      Some(jarFile)
		    }
	    }
	    
	    def getAlgoButUseLocalCopySinceRegistryIsBroken = {     
	      
	      // this directory is relative to the project root
	      // we'll use it to store executables just in case the registry fails
	      val EmergencyAlgorithmExecutableStorageDirectory = "emergency-algorithm-executable-storage"
	      
	      val extensions = List("",".jar")
	      var theFileWeNeed:File = null
	      
	      extensions foreach  
	        	(e => { val f = (new File(EmergencyAlgorithmExecutableStorageDirectory +
	        			File.separator + algoName + e))
	        			logger.debug("====> look for this filename: "+f.toString)
	        	       if (f.exists() && f.canRead())
	        	         theFileWeNeed = f})
	      
	      if  ( theFileWeNeed == null ) {
	    	  logger.error("!!!!> Couldn't find a copy of the file we need in the emergency storage space")
	    	  None
	      } else {
	        logger.debug("====> we are returning this copy of the executable from "+
	    	      "emergency file storage: "+theFileWeNeed.toString)
	    	  Some(theFileWeNeed.toString)
	      } 
	    }
	    
	    val SkipGettingAlgoExecutableFromRegistry = {
	       val skipProp = RuntimeProperties("skipgettingalgoexecutablefromregistry")
	       logger.debug("====> runtime property of 'skipgettingalgoexecutablefromregistry' is " + skipProp.toString)
		  
	        skipProp match {
	         case "true" => true
	         case "1" => true
	//	     case Some("true") => true
	//	     case Some("1") => true
		     case _ => false	     
		   }
	       
		}
	    
	    val optionFileNameString:Option[String] = if (!SkipGettingAlgoExecutableFromRegistry) {
	        getAlgoFromRegistryHelper
	    } else {
	        // due to bug documented here[1], i can't post algorithm executable
	        // to registry.  use a local copy instead
	        // [1] http://wso2.org/forum/thread/14971
	        logger.warn("===> We'll skip getting the algorithm executable from registry")
	        getAlgoButUseLocalCopySinceRegistryIsBroken
	    }
	    
	    if (optionFileNameString != None) {
	    	// trim the leading directory name from the file
	    	val filenameOnly = optionFileNameString.head.split(File.separator).last
	    	if (copyAlgoJarToWorkingDir) {
	    		logger.warn("====> DANGEROUS ... COPYING ALGO FILE TO WORKING DIR")
	    		FileUtils.copyFile(new File(optionFileNameString.head),	new File(workingDir + File.separator + filenameOnly))
	    	}
	    	Some(filenameOnly)
	    } else 
	    	None
  	}
  
  
  
}