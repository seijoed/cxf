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

package org.apache.cxf.transport.http_jetty.continuations;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.cxf.continuations.Continuation;
import org.apache.cxf.message.Message;
import org.apache.cxf.transport.http.AbstractHTTPDestination;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.server.AsyncContext;
import org.eclipse.jetty.server.Request;

public class JettyContinuationWrapper implements Continuation, ContinuationListener {
    volatile boolean isNew;
    volatile boolean isResumed;
    volatile boolean isPending;
    volatile Object obj;
    
    private Message message;
    private final AsyncContext context;
    private final Request req;
    
    public JettyContinuationWrapper(HttpServletRequest request, 
                                    HttpServletResponse resp, 
                                    Message m) {
        req = (Request)request;
        message = m;
        isNew = req.getAttribute(AbstractHTTPDestination.CXF_CONTINUATION_MESSAGE) == null;
        if (isNew) {
            req.setAttribute(AbstractHTTPDestination.CXF_CONTINUATION_MESSAGE,
                             message.getExchange().getInMessage());
            context = req.startAsync(req, resp);
            context.addContinuationListener(this);
            req.setAttribute(AbstractHTTPDestination.CXF_ASYNC_CONTEXT, context);
        } else {
            context = (AsyncContext)req.getAttribute(AbstractHTTPDestination.CXF_ASYNC_CONTEXT);
        }
    }

    public Object getObject() {
        return obj;
    }
    public void setObject(Object userObject) {
        obj = userObject;
    }

    public void resume() {
        isResumed = true;
        context.dispatch();
    }

    public boolean isNew() {
        return isNew;
    }

    public boolean isPending() {
        return isPending;
    }

    public boolean isResumed() {
        return isResumed;
    }

    public void reset() {
        context.complete();
        obj = null;
    }


    public boolean suspend(long timeout) {
        if (isPending) {
            return false;
        }
        context.setTimeout(timeout);
        isNew = false;
        // Need to get the right message which is handled in the interceptor chain
        message.getExchange().getInMessage().getInterceptorChain().suspend();
        isPending = true;
        return true;
    }
    
    protected Message getMessage() {
        Message m = message;
        if (m != null && m.getExchange().getInMessage() != null) {
            m = m.getExchange().getInMessage();
        }
        return m;
    }
    

    public void onComplete(org.eclipse.jetty.continuation.Continuation continuation) {
        getMessage().remove(AbstractHTTPDestination.CXF_CONTINUATION_MESSAGE);
        isPending = false;
    }

    public void onTimeout(org.eclipse.jetty.continuation.Continuation continuation) {
        isPending = false;
        context.dispatch();
    }
    
}
