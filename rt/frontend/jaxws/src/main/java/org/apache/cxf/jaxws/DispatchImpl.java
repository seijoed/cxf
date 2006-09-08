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

package org.apache.cxf.jaxws;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.bind.JAXBContext;
import javax.xml.ws.AsyncHandler;
import javax.xml.ws.Dispatch;
import javax.xml.ws.Response;
import javax.xml.ws.Service;

import org.apache.cxf.Bus;
import org.apache.cxf.BusException;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.interceptor.MessageSenderInterceptor;
import org.apache.cxf.jaxws.interceptors.DispatchInInterceptor;
import org.apache.cxf.jaxws.interceptors.DispatchOutInterceptor;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.ExchangeImpl;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.PhaseInterceptorChain;
import org.apache.cxf.phase.PhaseManager;
import org.apache.cxf.service.model.EndpointInfo;
import org.apache.cxf.transport.Conduit;
import org.apache.cxf.transport.ConduitInitiator;
import org.apache.cxf.transport.ConduitInitiatorManager;
import org.apache.cxf.transport.MessageObserver;

public class DispatchImpl<T> extends BindingProviderImpl implements Dispatch<T>, MessageObserver {
    private static final Logger LOG = LogUtils.getL7dLogger(DispatchImpl.class);

    private Bus bus;

    private Class<T> cl;
    private Executor executor;
    private JAXBContext context;
    private Service.Mode mode;

    private Endpoint endpoint;

    DispatchImpl(Bus b, Service.Mode m, Class<T> clazz, Executor e, Endpoint ep) {
        bus = b;
        cl = clazz;
        executor = e;
        mode = m;

        endpoint = ep;
    }

    DispatchImpl(Bus b,                 
                 Service.Mode m, 
                 JAXBContext ctx, 
                 Class<T> clazz, 
                 Executor e, 
                 Endpoint ep) {
        bus = b;
        executor = e;
        context = ctx;
        cl = clazz;
        mode = m;
        
        endpoint = ep;
    }

    public T invoke(T obj) {
        if (LOG.isLoggable(Level.INFO)) {
            LOG.info("Dispatch: invoke called");
        }

        Message message = endpoint.getBinding().createMessage();        
        
        if (context != null) {
            message.setContent(JAXBContext.class, context);
        }

        Exchange exchange = new ExchangeImpl();
        exchange.put(Service.Mode.class, mode);
        exchange.put(Class.class, cl);
        exchange.put(org.apache.cxf.service.Service.class, endpoint.getService());

        exchange.setOutMessage(message);
        message.setExchange(exchange);

        message.setContent(Object.class, obj);

        PhaseInterceptorChain chain = getDispatchOutChain();
        message.setInterceptorChain(chain);

        // setup conduit
        Conduit conduit = getConduit();
        exchange.setConduit(conduit);
        conduit.setMessageObserver(this);

        // execute chain
        chain.doIntercept(message);

        if (message.getContent(Exception.class) != null) {
            throw new RuntimeException(message.get(Exception.class));
        }

        // correlate response        
        if (conduit.getBackChannel() != null) {
            // process partial response and wait for decoupled response
        } else {
            // process response: send was synchronous so when we get here we can assume that the 
            // Exchange's inbound message is set and had been passed through the inbound interceptor chain.
        }

        synchronized (exchange) {
            Message inMsg = exchange.getInMessage();
            if (inMsg == null) {
                try {
                    exchange.wait();
                } catch (InterruptedException e) {
                    //TODO - timeout
                }
                inMsg = exchange.getInMessage();
            }
            if (inMsg.getContent(Exception.class) != null) {
                //TODO - exceptions 
                throw new RuntimeException(inMsg.getContent(Exception.class));
            }
            // TODO, just assume it's soap message, should handle the DOMSouce, SAXSource etc.
            return cl.cast(inMsg.getContent(Object.class));
        }
        //         populateResponseContext(objMsgContext);
        //         return cl.cast(objMsgContext.getReturn());
    }


    private PhaseInterceptorChain getDispatchOutChain() {
        PhaseManager pm = bus.getExtension(PhaseManager.class);
        PhaseInterceptorChain chain = new PhaseInterceptorChain(pm.getOutPhases());

        List<Interceptor> il = bus.getOutInterceptors();
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Interceptors contributed by bus: " + il);
        }
        chain.add(il);
        il = endpoint.getOutInterceptors();
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Interceptors contributed by endpoint: " + il);
        }
        chain.add(il);

        List<Interceptor> outInterceptors = new ArrayList<Interceptor>();
        outInterceptors.add(new MessageSenderInterceptor());
        outInterceptors.add(new DispatchOutInterceptor());

        chain.add(outInterceptors);

        return chain;
    }

    public void onMessage(Message message) {
        message = endpoint.getBinding().createMessage(message);

        //message.setContent(Service.Mode.class, mode);
        //message.setContent(Class.class, cl);
        message.put(Message.REQUESTOR_ROLE, Boolean.TRUE);

        PhaseManager pm = bus.getExtension(PhaseManager.class);
        PhaseInterceptorChain chain = new PhaseInterceptorChain(pm.getInPhases());
        message.setInterceptorChain(chain);

        List<Interceptor> il = bus.getInInterceptors();
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Interceptors contributed by bus: " + il);
        }
        chain.add(il);
        il = endpoint.getInInterceptors();
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Interceptors contributed by endpoint: " + il);
        }
        chain.add(il);

        List<Interceptor> inInterceptors = new ArrayList<Interceptor>();
        inInterceptors.add(new DispatchInInterceptor());
        chain.add(inInterceptors);

        // execute chain
        try {
            chain.doIntercept(message);
        } finally {
            synchronized (message.getExchange()) {
                message.getExchange().setInMessage(message);
                message.getExchange().notifyAll();
            }
        }
    }

    private Conduit getConduit() {
        EndpointInfo ei = endpoint.getEndpointInfo();
        String transportID = ei.getTransportId();
        try {
            ConduitInitiator ci = bus.getExtension(ConduitInitiatorManager.class)
                .getConduitInitiator(transportID);
            return ci.getConduit(ei);
        } catch (BusException ex) {
            // TODO: wrap in runtime exception
            ex.printStackTrace();
        } catch (IOException ex) {
            // TODO: wrap in runtime exception
            ex.printStackTrace();
        }

        return null;
    }

    public Future<?> invokeAsync(T obj, AsyncHandler<T> asyncHandler) {
        // TODO
        return null;
    }

    public Response<T> invokeAsync(T obj) {
        Response<T> response = null;
        executor.execute(null);
        // TODO
        return response;
    }

    public void invokeOneWay(T obj) {
        // TODO
    }
}
