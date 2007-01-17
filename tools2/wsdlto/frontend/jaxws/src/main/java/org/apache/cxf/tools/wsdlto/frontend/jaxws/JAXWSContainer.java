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

package org.apache.cxf.tools.wsdlto.frontend.jaxws;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import org.apache.cxf.common.i18n.Message;
import org.apache.cxf.service.model.ServiceInfo;
import org.apache.cxf.tools.common.ToolConstants;
import org.apache.cxf.tools.common.ToolContext;
import org.apache.cxf.tools.common.ToolException;
import org.apache.cxf.tools.common.toolspec.ToolSpec;
import org.apache.cxf.tools.validator.internal.AbstractValidator;
import org.apache.cxf.tools.wsdlto.WSDLToJavaContainer;
import org.apache.cxf.tools.wsdlto.frontend.jaxws.validator.XMLFormatValidator;

public class JAXWSContainer extends WSDLToJavaContainer {
    
    private static final String TOOL_NAME = "wsdl2java";
    
    public JAXWSContainer(ToolSpec toolspec) throws Exception {
        super(TOOL_NAME, toolspec);
    }

    public Set<String> getArrayKeys() {
        Set<String> set = super.getArrayKeys();
        set.add(ToolConstants.CFG_BINDING);
        return set;
    }

    public void validate(ToolContext env) throws ToolException {
        super.validate(env);
        File tmpfile = new File("");
        if (env.containsKey(ToolConstants.CFG_BINDING)) {
            String[] bindings = (String[])env.get(ToolConstants.CFG_BINDING);
            for (int i = 0; i < bindings.length; i++) {               
                
                File bindingFile = null;
                try {
                    URI bindingURI = new URI(bindings[i]);
                    if (!bindingURI.isAbsolute()) {
                        bindingURI = tmpfile.toURI().resolve(bindingURI);
                    }
                    bindingFile = new File(bindingURI);
                } catch (URISyntaxException e) {
                    bindingFile = new File(bindings[i]);
                }
                bindings[i] = bindingFile.toURI().toString();
                if (!bindingFile.exists()) {
                    Message msg = new Message("FILE_NOT_EXIST", LOG, bindings[i]);
                    throw new ToolException(msg);
                } else if (bindingFile.isDirectory()) {
                    Message msg = new Message("NOT_A_FILE", LOG, bindings[i]);
                    throw new ToolException(msg);
                }
            }
            env.put(ToolConstants.CFG_BINDING, bindings);
        }        
    }

    public List<AbstractValidator> getServiceValidators() {
        List<AbstractValidator> validators = new ArrayList<AbstractValidator>();
        validators.add(new XMLFormatValidator(context.get(ServiceInfo.class)));
        return validators;
    }
    
}
