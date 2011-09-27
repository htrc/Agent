package test.scala

import org.specs._
import org.specs.runner.JUnit3
import org.specs.runner.ConsoleRunner
import org.specs.matcher._
import org.specs.specification._
import htrcagent._
import org.slf4j.{Logger,LoggerFactory}
import scala.collection.mutable.HashMap
import scala.collection.JavaConversions._
import java.io.{File, BufferedReader, BufferedWriter, InputStreamReader, 
  FileOutputStream, FileInputStream, FileReader, FileWriter}
import scala.collection.mutable.ListBuffer
import edu.indiana.d2i.registry._
import java.net.URI
import java.util.UUID
//class AgentWebTemplateSpecsAsTest extends JUnit3(AgentWebTemplateTestSpecs)
//object AgentWebTemplateTestSpecsRunner extends ConsoleRunner(AgentWebTemplateTestSpecs)


class AgentWebTemplateTestSpecs extends Specification {
    private val testlogger = LoggerFactory.getLogger(getClass)
	"This string" should {
	  "contain 11 characters" in {
	    "This string" must have size(11)
	  }
	}
	
	"A simpleSysCall of 'ls'" should {
	  "have more than zero output lines" in {
	    val output = SysCall.sysCallSimple(List("ls"),".")
	  }
	}
	
	"AgentUtils.fixEscaped of a backslash-colon" should {
	  "result in a colon only" in {
	    val output = AgentUtils.fixEscaped("\\:") 
	    testlogger.info("result of replacing escaped colon (\\:) -> "+output)
	    output must beEqualTo (":")
	  }
	}
	
	"AgentUtils.fixEscaped of a double-backslash" should {
	  "result in a single backslash" in {
	    val output = AgentUtils.fixEscaped("\\\\")
	    testlogger.info("result of replacing double-backslash (\\\\) -> " + output)
	    output must beEqualTo ("\\")
	  }
	}
	
	"AgentUtils.fixEscaped of a user argument for simplescript" should {
	  "result in the tab character being preserved" in {
	    val argFromProps = TestProps.props.get("usrArg").toString
	    testlogger.debug("arg from props: "+argFromProps)
	    val output = AgentUtils.fixEscaped(argFromProps)
	    output must beEqualTo ("w.*\\t5")
	  }
	}

	
	"working directory creation" should {
	  "result in a directory that exists, and is readable, writeable, executable" in {
	    val workingDir = AgentUtils.createWorkingDirectory
	    val wdFile = (new File(workingDir))
	    (wdFile.exists() && wdFile.canRead() && wdFile.canWrite() &&
	      wdFile.canExecute()) mustBe(true)
	  }
	}
	
	object TestProps {
	   /*
	     *  example of the arguments delivered to the function:


 eprMap key: solrEPR      val: http://coffeetree.cs.indiana.edu:8983/solr
 eprMap key: registryEPR  val: greetings.
 eprMap key: cassandraEPR val: smoketree.cs.indiana.edu:9160
 userArgs = List(w.*\t5)
 first volume ID = inu.30000125550933
 volumesTextFile = vols-f371fc67-bb2e-43ab-b22f-2e4c1beb4b40.txt

	     */
	    
	    val eprMap = new HashMap[String,String]
	    eprMap.put("solrEPR","http://coffeetree.cs.indiana.edu:8983/solr")
	    eprMap.put("registryEPR","greetings.")
	    eprMap.put("cassandraEPR","smoketree.cs.indiana.edu:9160")
	    val userArgs = List("w.*\t5")
	    val volumeIDs = List("inu.30000125550933")
	    val volumesTextFile = "foobar.txt"
	    val props = AgentUtils.createAlgorithmProperties(eprMap, 
             userArgs=userArgs, volumeIDs,volumesTextFile )
             
	}
	"Properties object generation" should {
	  "result in an java.util.Properties that doesn't contain extraneous backslashes" in {
          
        testlogger.debug("props object looks like this: " + TestProps.props.toString)
        
        val noneShouldBeTrue = 
          for (p <- TestProps.props.keys) 
        	  yield (TestProps.props.get(p).toString.matches(".*\\\\\\\\.*") || 
        			  TestProps.props.get(p).toString.matches(".*\\\\:.*"))
        val oneMatched = noneShouldBeTrue.reduceLeft(_ || _)
        oneMatched must notBe(true)
        
	    
	  }
	  
	  "have a correctly formatted usrArg for the simplescript" in {
	    // should look like this: usrArg = w.*\t5
		val usrArgShouldHaveATabChar = 
		  TestProps.props.get("usrArg").toString.matches("w.*\\\\t5")
		usrArgShouldHaveATabChar must be(true)
	
	  }
	}
	
	"Properties output file creation" should {
	  "result in a File that doesn't contain extraneous backslashes" in {
	    val workingDir = AgentUtils.createWorkingDirectory
	    val propFile = new File(workingDir + File.separator + "props.cfg")
	    //was: TestProps.props.store(new FileOutputStream(propFile),"propfile")
	    // but now use:
	    AgentUtils.storePropertiesFileForAlgorithm(TestProps.props,
	        new BufferedWriter(new FileWriter(propFile)))
	    // read in the file
	    val in = (new BufferedReader(new FileReader(propFile)))
	    val lines: ListBuffer[String] = ListBuffer() 
	    while (in.ready()) {
	      val newline = in.readLine()
	      testlogger.debug("====> PROPS FILE CHECK: Got a new line: "+newline)        
	      lines += (newline)
	    }
	    
	    // check each line for double backslashes or backslash-colon
	    val anyLineHasTooManySlashes = 
	      (for (l <- lines) yield  l.matches(".*\\\\\\\\.*") || 
	          l.matches(".*\\\\:.*")  ).reduceLeft(_ || _)
	          
	    anyLineHasTooManySlashes must notBe(true)
	  }
	}
	
	"The registryClient" should {
	   val keyStore = "config" + File.separator + "wso2carbon.jks" 
       val keyStoreFile = (new File(keyStore))
	   System.setProperty("javax.net.ssl.trustStore", keyStore)
       System.setProperty("javax.net.ssl.trustStorePassword", "wso2carbon")
       System.setProperty("javax.net.ssl.trustStoreType", "JKS")
       val regClient = new RegistryClient
	  
       "successfully  get SOLR index EPR" in { 
	    val resultURI = new URI(regClient.getSolrIndexServiceURI("htrc-apache-solr-search"))
	    resultURI must notBeNull
	    
	  }
	  
	  "successfully get repository EPR" in {   
	    val resultURI = new URI(regClient.getSolrIndexServiceURI("htrc-cassandra-repository"))
	    resultURI must notBeNull
	  }
	  
	  "be able to post a resource to the registry without throwing an exception" in {
	    import java.net.URLEncoder
	    // args = pathToResource, resourceToPost, metadataToPost
	    val fakeResultID = "fakeResult-"+UUID.randomUUID
	    val fakeName = URLEncoder.encode("urn:publicid:IDN+bogusID.org+user+A1Winner","ISO-8859-1")
	    //val fakeName = "simplename"
	    val outString =  regClient.postResourse(   
	        "/results/"+fakeName+"/"+fakeResultID,
	        "this is a test result",
	        "<blank>there is no metadata here</blank"
	        ) 
	     testlogger.error("this tends to always throw an error...")
	    
	  } 
	}
	
	
	
}