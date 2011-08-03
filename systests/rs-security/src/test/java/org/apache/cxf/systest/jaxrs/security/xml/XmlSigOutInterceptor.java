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
package org.apache.cxf.systest.jaxrs.security.xml;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import javax.security.auth.callback.CallbackHandler;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.dom.DOMSource;

import org.w3c.dom.Document;

import org.apache.cxf.common.classloader.ClassLoaderUtils;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.jaxrs.provider.JAXBElementProvider;
import org.apache.cxf.jaxrs.provider.ProviderFactory;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageContentsList;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.staxutils.W3CDOMStreamWriter;
import org.apache.cxf.systest.jaxrs.security.common.CryptoLoader;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.ws.security.WSPasswordCallback;
import org.apache.ws.security.WSSConfig;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.components.crypto.CryptoType;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.apache.xml.security.utils.Constants;
import org.apache.xml.security.utils.ElementProxy;
import org.opensaml.xml.signature.SignatureConstants;


public class XmlSigOutInterceptor extends AbstractPhaseInterceptor<Message> {
    private static final Logger LOG = 
        LogUtils.getL7dLogger(XmlSigOutInterceptor.class);
    
    static {
        WSSConfig.init();
    }
    
    private boolean createReferenceId = true;
    
    public XmlSigOutInterceptor() {
        super(Phase.WRITE);
    } 

    public void setCreateReferenceId(boolean create) {
        createReferenceId = create;
    }
    
    public void handleMessage(Message message) throws Fault {
        try {
            Object body = getRequestBody(message);
            if (body == null) {
                return;
            }
            Document doc = getDomDocument(body, message);
            if (doc == null) {
                return;
            }
 
            createEnvelopedSignature(message, doc);
            message.setContent(List.class, 
                               new MessageContentsList(new DOMSource(doc)));
        } catch (Exception ex) {
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            LOG.warning(sw.toString());
            throw new Fault(new RuntimeException(ex.getMessage() + ", stacktrace: " + sw.toString()));
        }
    }
    
    // enveloping & detached sigs will be supported too
    private void createEnvelopedSignature(Message message, Document doc) 
        throws Exception {
        
        String userNameKey = SecurityConstants.SIGNATURE_USERNAME;
        
        CryptoLoader loader = new CryptoLoader();
        Crypto crypto = loader.getCrypto(message, 
                                         SecurityConstants.SIGNATURE_CRYPTO,
                                         SecurityConstants.SIGNATURE_PROPERTIES);
        if (crypto == null) {
            crypto = loader.getCrypto(message, 
                                      SecurityConstants.ENCRYPT_CRYPTO,
                                      SecurityConstants.ENCRYPT_PROPERTIES);
            userNameKey = SecurityConstants.ENCRYPT_USERNAME;
        }
        String user = getUserName(message, crypto, userNameKey);
         
        if (StringUtils.isEmpty(user)) {
            return;
        }

        String password = getPassword(message, user, WSPasswordCallback.SIGNATURE);
        //---
        // 
        // prepare to sign the SAML token
        CryptoType cryptoType = new CryptoType(CryptoType.TYPE.ALIAS);
        cryptoType.setAlias(user);
        X509Certificate[] issuerCerts = crypto.getX509Certificates(cryptoType);
        if (issuerCerts == null) {
            throw new WSSecurityException(
                "No issuer certs were found to sign the document using issuer name: " 
                + user);
        }
        
        String sigAlgo = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA1;
        String pubKeyAlgo = issuerCerts[0].getPublicKey().getAlgorithm();
        if (pubKeyAlgo.equalsIgnoreCase("DSA")) {
            sigAlgo = XMLSignature.ALGO_ID_SIGNATURE_DSA;
        }
        PrivateKey privateKey = null;
        try {
            privateKey = crypto.getPrivateKey(user, password);
        } catch (Exception ex) {
            throw new WSSecurityException(ex.getMessage(), ex);
        }
        //
        ElementProxy.setDefaultPrefix(Constants.SignatureSpecNS, "ds");
        
        String referenceId = "";
        if (createReferenceId) {
            String id = UUID.randomUUID().toString();
            referenceId = "#" + id;
            doc.getDocumentElement().setAttribute("ID", id);    
        }
        
        XMLSignature sig = new XMLSignature(doc, "", sigAlgo);
        doc.getDocumentElement().appendChild(sig.getElement());
        Transforms transforms = new Transforms(doc);
        transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
        transforms.addTransform(Transforms.TRANSFORM_C14N_EXCL_OMIT_COMMENTS);
        
        sig.addDocument("", transforms, Constants.ALGO_ID_DIGEST_SHA1, referenceId, null);
        
        sig.addKeyInfo(issuerCerts[0]);
        sig.addKeyInfo(issuerCerts[0].getPublicKey());
        sig.sign(privateKey);
    }
    
    private Object getRequestBody(Message message) {
        MessageContentsList objs = MessageContentsList.getContentsList(message);
        if (objs == null || objs.size() == 0) {
            return null;
        } else {
            return objs.get(0);
        }
    }
    
    @SuppressWarnings("unchecked")
    private Document getDomDocument(Object body, Message m) throws Exception {
        
        if (body instanceof Document) {
            return (Document)body;
        }
        
        ProviderFactory pf = ProviderFactory.getInstance(m);
        
        Object providerObject = pf.createMessageBodyWriter(body.getClass(), 
                                   body.getClass(), new Annotation[]{}, 
                                   MediaType.APPLICATION_XML_TYPE, m);
        if (!(providerObject instanceof JAXBElementProvider)) {
            return null;
        }
        JAXBElementProvider provider = (JAXBElementProvider)providerObject;
        W3CDOMStreamWriter writer = new W3CDOMStreamWriter();
        m.setContent(XMLStreamWriter.class, writer);
        provider.writeTo(body, body.getClass(), 
                         body.getClass(), new Annotation[]{},
                         MediaType.APPLICATION_XML_TYPE,
                         (MultivaluedMap)m.get(Message.PROTOCOL_HEADERS), null);
        return writer.getDocument();
    }
    
 // This code will be moved to a common utility class
    private String getUserName(Message message, Crypto crypto, String userNameKey) {
        String user = (String)message.getContextualProperty(userNameKey);
        if (crypto != null && StringUtils.isEmpty(user)) {
            try {
                user = crypto.getDefaultX509Identifier();
            } catch (WSSecurityException e1) {
                throw new Fault(e1);
            }
        }
        return user;
    }
    
    
    private String getPassword(Message message, String userName, int type) {
        CallbackHandler handler = getCallbackHandler(message);
        if (handler == null) {
            return null;
        }
        
        WSPasswordCallback[] cb = {new WSPasswordCallback(userName, type)};
        try {
            handler.handle(cb);
        } catch (Exception e) {
            return null;
        }
        
        //get the password
        String password = cb[0].getPassword();
        return password == null ? "" : password;
    }
    
    private CallbackHandler getCallbackHandler(Message message) {
        //Then try to get the password from the given callback handler
        Object o = message.getContextualProperty(SecurityConstants.CALLBACK_HANDLER);
    
        CallbackHandler handler = null;
        if (o instanceof CallbackHandler) {
            handler = (CallbackHandler)o;
        } else if (o instanceof String) {
            try {
                handler = (CallbackHandler)ClassLoaderUtils
                    .loadClass((String)o, this.getClass()).newInstance();
            } catch (Exception e) {
                handler = null;
            }
        }
        return handler;
    }
    
    
}