/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
*/
package org.apache.uima.ducc.ps.service.processor.uima;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
//import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.uima.UIMAFramework;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.metadata.AnalysisEngineMetaData;
import org.apache.uima.cas.CAS;
import org.apache.uima.ducc.ps.service.IServiceState;
import org.apache.uima.ducc.ps.service.ServiceConfiguration;
//import org.apache.uima.ducc.ps.service.dgen.DeployableGeneration;
import org.apache.uima.ducc.ps.service.errors.IServiceErrorHandler;
import org.apache.uima.ducc.ps.service.errors.IServiceErrorHandler.Action;
import org.apache.uima.ducc.ps.service.metrics.IWindowStats;
import org.apache.uima.ducc.ps.service.metrics.builtin.ProcessWindowStats;
//import org.apache.uima.ducc.ps.service.jmx.JmxAEProcessInitMonitor;
import org.apache.uima.ducc.ps.service.monitor.IServiceMonitor;
import org.apache.uima.ducc.ps.service.monitor.builtin.RemoteStateObserver;
import org.apache.uima.ducc.ps.service.processor.IProcessResult;
import org.apache.uima.ducc.ps.service.processor.IServiceProcessor;
import org.apache.uima.ducc.ps.service.processor.IServiceResultSerializer;
import org.apache.uima.ducc.ps.service.processor.uima.utils.PerformanceMetrics;
import org.apache.uima.ducc.ps.service.processor.uima.utils.UimaMetricsGenerator;
import org.apache.uima.ducc.ps.service.processor.uima.utils.UimaResultDefaultSerializer;
import org.apache.uima.ducc.ps.service.utils.UimaSerializer;
import org.apache.uima.ducc.ps.service.utils.UimaUtils;
import org.apache.uima.resource.Resource;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.ResourceManager;
import org.apache.uima.resource.ResourceSpecifier;
import org.apache.uima.util.CasPool;
import org.apache.uima.util.Level;
import org.apache.uima.util.Logger;
import org.apache.uima.util.XMLInputSource;

public class UimaServiceProcessor extends AbstractServiceProcessor implements IServiceProcessor {
	public static final String IMPORT_BY_NAME_PREFIX = "*importByName:";
	Logger logger = UIMAFramework.getLogger(UimaServiceProcessor.class);
   // Map to store DuccUimaSerializer instances. Each has affinity to a thread
	private IServiceResultSerializer resultSerializer;
	// stores AE instance pinned to a thread
	private ThreadLocal<AnalysisEngine> threadLocal = 
			new ThreadLocal<> ();
    private ReentrantLock initStateLock = new ReentrantLock();
    private boolean sendInitializingState = true;
	private ResourceManager rm = 
			UIMAFramework.newDefaultResourceManager();;
    private CasPool casPool = null;
	private int scaleout=1;
    private String analysisEngineDescriptor;
    private AnalysisEngineMetaData analysisEngineMetadata;
	// Platform MBean server if one is available (Java 1.5 only)
	private static Object platformMBeanServer;
	private ServiceConfiguration serviceConfiguration;
	private IServiceMonitor monitor;
	private AtomicInteger numberOfInitializedThreads = new AtomicInteger();
	private IServiceErrorHandler errorHandler;
	
	static {
		// try to get platform MBean Server (Java 1.5 only)
		try {
			Class<?> managementFactory = Class.forName("java.lang.management.ManagementFactory");
			Method getPlatformMBeanServer = managementFactory.getMethod("getPlatformMBeanServer", new Class[0]);
			platformMBeanServer = getPlatformMBeanServer.invoke(null, (Object[]) null);
		} catch (Exception e) {
			platformMBeanServer = null;
		}
	}	
	
	public UimaServiceProcessor(String analysisEngineDescriptor) {
		this(analysisEngineDescriptor,  new UimaResultDefaultSerializer(), new ServiceConfiguration());
	}
	public UimaServiceProcessor(String analysisEngineDescriptor, ServiceConfiguration serviceConfiguration) {
		this(analysisEngineDescriptor,  new UimaResultDefaultSerializer(), serviceConfiguration);
	}
	public UimaServiceProcessor(String analysisEngineDescriptor, IServiceResultSerializer resultSerializer, ServiceConfiguration serviceConfiguration) {
		this.analysisEngineDescriptor = analysisEngineDescriptor;
		this.resultSerializer = resultSerializer;
		this.serviceConfiguration = serviceConfiguration;
		// start a thread which will collect AE initialization state
		launchStateInitializationCollector();
		if (serviceConfiguration.getJpType() != null) {
		  serializerMap = new HashMap<>();
		}
		// check if error window override has been set via -D
		if ( serviceConfiguration.getMaxErrors() != null ) {
			this.maxErrors = Integer.parseInt(serviceConfiguration.getMaxErrors());
		}
		// check if error window override has been set via -D
		if ( serviceConfiguration.getErrorWindowSize() != null ) {
			this.windowSize = Integer.parseInt(serviceConfiguration.getErrorWindowSize());
		}
	}
	/*
	 * Defines error handling parameters
	 * 
	 * @param maxErrors - maximum error threshold within an error window
	 * @param windowSize - error window size
	 */
	public void setErrorHandlerWindow(int maxErrors, int windowSize) {
		this.maxErrors = maxErrors;
		this.windowSize = windowSize;
	}
	private void launchStateInitializationCollector() {
		monitor =
				new RemoteStateObserver(serviceConfiguration, logger);
	}
	public void setScaleout(int howManyThreads) {
		this.scaleout = howManyThreads;
	}
	public int getScaleout() {
		return scaleout;
	}

	@Override
	public void initialize() {

		if ( logger.isLoggable(Level.FINE)) {
			logger.log(Level.FINE, "Process Thread:"+ Thread.currentThread().getName()+" Initializing AE");
			
		}
		errorHandler = getErrorHandler(logger);
		
		try {
			// multiple threads may call this method. Send initializing state once
			initStateLock.lockInterruptibly();
			if ( sendInitializingState ) {
				sendInitializingState = false; // send init state once
				monitor.onStateChange(IServiceState.State.Initializing.toString(), new Properties());
			}
		} catch( Exception e) {
			
		} finally {
			initStateLock.unlock();
		}


	    HashMap<String,Object> paramsMap = new HashMap<>();
        paramsMap.put(Resource.PARAM_RESOURCE_MANAGER, rm);
	    paramsMap.put(AnalysisEngine.PARAM_MBEAN_SERVER, platformMBeanServer);
	    
		try {
		
			XMLInputSource is =
					UimaUtils.getXMLInputSource(analysisEngineDescriptor);
			String aed = is.getURL().toString();
			ResourceSpecifier rSpecifier =
			    UimaUtils.getResourceSpecifier(aed);

			AnalysisEngine ae = UIMAFramework.produceAnalysisEngine(rSpecifier,
					paramsMap);
			// pin AE instance to this thread
			threadLocal.set(ae);

			synchronized(UimaServiceProcessor.class) {
		    	if ( casPool == null ) {
		    		initializeCasPool(ae.getAnalysisEngineMetaData());
		    	}
			}
			// every process thread has its own uima deserializer
			if (serviceConfiguration.getJpType() != null) {
			  serializerMap.put(Thread.currentThread().getId(), new UimaSerializer());
			}

		} catch (Exception e) {
			logger.log(Level.WARNING, null, e);
			monitor.onStateChange(IServiceState.State.FailedInitialization.toString(), new Properties());
			throw new RuntimeException(e);

		} 
		if ( logger.isLoggable(Level.INFO)) {
			logger.log(Level.INFO, "Process Thread:"+ Thread.currentThread().getName()+" Done Initializing AE");
			
		}
		if ( numberOfInitializedThreads.incrementAndGet() == scaleout ) {
			super.delay(logger, DEFAULT_INIT_DELAY);
			monitor.onStateChange(IServiceState.State.Running.toString(), new Properties());
		}
	}

	private void initializeCasPool(AnalysisEngineMetaData aeMeta) throws ResourceInitializationException {
		Properties props = new Properties();
		props.setProperty(UIMAFramework.CAS_INITIAL_HEAP_SIZE, "1000");

		analysisEngineMetadata = aeMeta;
		casPool = new CasPool(scaleout, analysisEngineMetadata, rm);
	}

	@Override
	public IProcessResult process(String serializedTask) {
		AnalysisEngine ae = null;

		CAS cas = casPool.getCas();
		IProcessResult result;
		
		try {
		  // DUCC JP  services are given a serialized CAS ... others just the doc-text for a CAS
			if (serviceConfiguration.getJpType() != null) {
			  getUimaSerializer().deserializeCasFromXmi(serializedTask, cas);
			} else {
			  cas.setDocumentText(serializedTask);
			  cas.setDocumentLanguage("en");
			}
			// check out AE instance pinned to this thread
			ae = threadLocal.get();
			// get AE metrics before calling process(). Needed for
			// computing a delta
			List<PerformanceMetrics> beforeAnalysis = 
					UimaMetricsGenerator.get(ae);
			
			// *****************************************************
			// PROCESS
			// *****************************************************
			ae.process(cas);
			
			// *****************************************************
			// No exception in process() , fetch metrics 
			// *****************************************************
			List<PerformanceMetrics> afterAnalysis = 
					UimaMetricsGenerator.get(ae);

			// get the delta
			List<PerformanceMetrics> casMetrics = 
					UimaMetricsGenerator.getDelta( afterAnalysis, beforeAnalysis);
			
			successCount.incrementAndGet();
			errorCountSinceLastSuccess.set(0);
			return new UimaProcessResult(resultSerializer.serialize(casMetrics));
		} catch( Exception e ) {
			logger.log(Level.WARNING,"",e);
			IWindowStats stats = 
					new ProcessWindowStats(errorCount.incrementAndGet(), 
							successCount.get(), 
							errorCountSinceLastSuccess.incrementAndGet());
			Action action = 
					errorHandler.handleProcessError(e, this, stats);

			result = new UimaProcessResult(e, action);
			return result;
 		} finally {
			
			if (cas != null) {
				casPool.releaseCas(cas);
			}
		}
	}

	
	public void setErrorHandler(IServiceErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	@Override
	public void stop() {
		logger.log(Level.INFO,this.getClass().getName()+" stop() called");
		try {
			AnalysisEngine ae = threadLocal.get();
			if ( ae != null ) {
				ae.destroy();
			}
			super.stop();

		} catch( Exception e) {
			logger.log(Level.WARNING, "stop", e);
		} 
	}
	/*
	  // Build just an AE from parts and return the filename
	  // (DD's are converted in UimaAsProcessContainer.parseDD)
	  protected String buildDeployable(ServiceConfiguration serviceConfiguration) {
		  try {
			  String jpType = serviceConfiguration.getJpType();//getPropertyString("ducc.deploy.JpType");
			  if(jpType == null) {
				  jpType = "uima";
			  }
			  if(jpType.equalsIgnoreCase("uima-as")) {
				  logger.log(Level.WARNING,"ERROR - should not be called for type="+jpType);
			  }
			  else {
				  DeployableGeneration dg = new DeployableGeneration(serviceConfiguration);
				  return dg.generate(true);
			  }
		  }
		  catch(Exception e) {
			  e.printStackTrace();
			  logger.log(Level.WARNING,"buildDeployable",e);
		  }
		  return null;
	  }
*/
}

