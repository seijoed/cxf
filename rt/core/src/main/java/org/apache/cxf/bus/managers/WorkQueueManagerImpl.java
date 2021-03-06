/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.bus.managers;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Resource;
import javax.management.JMException;

import org.apache.cxf.Bus;
import org.apache.cxf.buslifecycle.BusLifeCycleListener;
import org.apache.cxf.buslifecycle.BusLifeCycleManager;
import org.apache.cxf.common.injection.NoJSR250Annotations;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.configuration.ConfiguredBeanLocator;
import org.apache.cxf.management.InstrumentationManager;
import org.apache.cxf.workqueue.AutomaticWorkQueue;
import org.apache.cxf.workqueue.AutomaticWorkQueueImpl;
import org.apache.cxf.workqueue.WorkQueueManager;

@NoJSR250Annotations(unlessNull = "bus")
public class WorkQueueManagerImpl implements WorkQueueManager {

    private static final Logger LOG =
        LogUtils.getL7dLogger(WorkQueueManagerImpl.class);

    Map<String, AutomaticWorkQueue> namedQueues 
        = new ConcurrentHashMap<String, AutomaticWorkQueue>();
    
    boolean inShutdown;
    InstrumentationManager imanager;
    Bus bus;  
    
    public WorkQueueManagerImpl() {
        
    }
    public WorkQueueManagerImpl(Bus b) {
        setBus(b);
    }
    
    public Bus getBus() {
        return bus;
    }
    
    @Resource
    public final void setBus(Bus bus) {        
        this.bus = bus;
        if (null != bus) {
            bus.setExtension(this, WorkQueueManager.class);
            imanager = bus.getExtension(InstrumentationManager.class);
            if (null != imanager) {
                try {
                    imanager.register(new WorkQueueManagerImplMBeanWrapper(this));
                } catch (JMException jmex) {
                    LOG.log(Level.WARNING , jmex.getMessage(), jmex);
                }
            }
            ConfiguredBeanLocator locator = bus.getExtension(ConfiguredBeanLocator.class);
            Collection<? extends AutomaticWorkQueue> q = locator
                    .getBeansOfType(AutomaticWorkQueue.class);
            if (q != null) {
                for (AutomaticWorkQueue awq : q) {
                    addNamedWorkQueue(awq.getName(), awq);
                }
            }
            
            if (!namedQueues.containsKey("default")) {
                AutomaticWorkQueue defaultQueue 
                    = locator.getBeanOfType("cxf.default.workqueue", AutomaticWorkQueue.class);
                if (defaultQueue != null) {
                    addNamedWorkQueue("default", defaultQueue);
                }
            }
            
            bus.getExtension(BusLifeCycleManager.class)
                .registerLifeCycleListener(new WQLifecycleListener());
        }
    }

    public synchronized AutomaticWorkQueue getAutomaticWorkQueue() {
        AutomaticWorkQueue defaultQueue = getNamedWorkQueue("default");
        if (defaultQueue == null) {
            defaultQueue = createAutomaticWorkQueue();
        }
        return defaultQueue;
    }

    public synchronized void shutdown(boolean processRemainingTasks) {
        inShutdown = true;
        for (AutomaticWorkQueue q : namedQueues.values()) {
            if (q instanceof AutomaticWorkQueueImpl) {
                AutomaticWorkQueueImpl impl = (AutomaticWorkQueueImpl)q;
                if (impl.isShared()) {
                    synchronized (impl) {
                        impl.removeSharedUser();
                        
                        if (impl.getShareCount() == 0 
                            && imanager != null 
                            && imanager.getMBeanServer() != null) {
                            try {
                                imanager.unregister(new WorkQueueImplMBeanWrapper(impl, this));
                            } catch (JMException jmex) {
                                LOG.log(Level.WARNING , jmex.getMessage(), jmex);
                            }
                        }
                    }
                } else {
                    q.shutdown(processRemainingTasks);
                }
            } else {
                q.shutdown(processRemainingTasks);
            }
        }

        synchronized (this) {
            notifyAll();
        }
    }

    public void run() {
        synchronized (this) {
            while (!inShutdown) {
                try {            
                    wait();
                } catch (InterruptedException ex) {
                    // ignore
                }
            }
            for (AutomaticWorkQueue q : namedQueues.values()) {
                while (!q.isShutdown()) {
                    try {            
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                        // ignore
                    }
                }
            }
        }
        for (java.util.logging.Handler h : LOG.getHandlers())  {
            h.flush();
        }
        
    }

    public AutomaticWorkQueue getNamedWorkQueue(String name) {
        return namedQueues.get(name);
    }
    public final void addNamedWorkQueue(String name, AutomaticWorkQueue q) {
        namedQueues.put(name, q);
        if (q instanceof AutomaticWorkQueueImpl) {
            AutomaticWorkQueueImpl impl = (AutomaticWorkQueueImpl)q;
            if (impl.isShared()) {
                synchronized (impl) {
                    if (impl.getShareCount() == 0 
                        && imanager != null 
                        && imanager.getMBeanServer() != null) {
                        try {
                            imanager.register(new WorkQueueImplMBeanWrapper((AutomaticWorkQueueImpl)q, this));
                        } catch (JMException jmex) {
                            LOG.log(Level.WARNING , jmex.getMessage(), jmex);
                        }
                    }
                    impl.addSharedUser();
                }
            } else if (imanager != null) {
                try {
                    imanager.register(new WorkQueueImplMBeanWrapper((AutomaticWorkQueueImpl)q, this));
                } catch (JMException jmex) {
                    LOG.log(Level.WARNING , jmex.getMessage(), jmex);
                }
            }
        }
    }
    
    private AutomaticWorkQueue createAutomaticWorkQueue() {        
        AutomaticWorkQueue q = new AutomaticWorkQueueImpl("default");
        addNamedWorkQueue("default", q);
        return q;
    }
    
    
    class WQLifecycleListener implements BusLifeCycleListener {
        public void initComplete() {
            
        }
        public void preShutdown() {
            shutdown(true);
        }
        public void postShutdown() {
        }
    }
}
