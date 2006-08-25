package org.objectweb.celtix.bindings.soap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jws.WebParam;
import javax.jws.soap.SOAPBinding.ParameterStyle;
import javax.jws.soap.SOAPBinding.Style;
import javax.xml.namespace.QName;
import javax.xml.soap.Detail;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.MimeHeader;
import javax.xml.soap.MimeHeaders;
import javax.xml.soap.SOAPBody;
import javax.xml.soap.SOAPConstants;
import javax.xml.soap.SOAPElement;
import javax.xml.soap.SOAPEnvelope;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPFactory;
import javax.xml.soap.SOAPFault;
import javax.xml.soap.SOAPHeader;
import javax.xml.soap.SOAPMessage;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.ws.Holder;
import javax.xml.ws.ProtocolException;
import javax.xml.ws.WebFault;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.handler.MessageContext;
import javax.xml.ws.handler.soap.SOAPMessageContext;
import javax.xml.ws.soap.SOAPBinding;
import javax.xml.ws.soap.SOAPFaultException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.objectweb.celtix.bindings.AbstractBindingImpl;
import org.objectweb.celtix.bindings.DataBindingCallback;
import org.objectweb.celtix.bindings.DataReader;
import org.objectweb.celtix.bindings.DataWriter;
import org.objectweb.celtix.bus.handlers.HandlerChainInvoker;
import org.objectweb.celtix.common.logging.LogUtils;
import org.objectweb.celtix.context.InputStreamMessageContext;
import org.objectweb.celtix.context.ObjectMessageContext;
import org.objectweb.celtix.context.OutputStreamMessageContext;
import org.objectweb.celtix.datamodel.soap.W3CConstants;
import org.objectweb.celtix.handlers.HandlerInvoker;
import org.objectweb.celtix.helpers.NSStack;
import org.objectweb.celtix.helpers.NodeUtils;

import static org.objectweb.celtix.datamodel.soap.SOAPConstants.FAULTCODE_CLIENT;
import static org.objectweb.celtix.datamodel.soap.SOAPConstants.FAULTCODE_SERVER;
import static org.objectweb.celtix.datamodel.soap.SOAPConstants.FAULTCODE_VERSIONMISMATCH;
import static org.objectweb.celtix.datamodel.soap.SOAPConstants.HEADER_MUSTUNDERSTAND;

public class SOAPBindingImpl extends AbstractBindingImpl implements SOAPBinding {
    private static final Logger LOG = LogUtils.getL7dLogger(SOAPBindingImpl.class);
    protected final MessageFactory msgFactory;
    protected final SOAPFactory soapFactory;
    protected final boolean isServer;
    private NSStack nsStack;
    private QName faultCode;

    public SOAPBindingImpl(boolean server) {
        try {
            isServer = server;
            msgFactory = MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL);
            soapFactory = SOAPFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL);
            faultCode = isServer ? FAULTCODE_SERVER
                                 : FAULTCODE_CLIENT;
        } catch (SOAPException se) {
            LOG.log(Level.SEVERE, "SAAJ_FACTORY_CREATION_FAILURE_MSG", se);
            throw new WebServiceException(se.getMessage());
        }
    }

    // --- AbstractBindingImpl interface ---

    public MessageContext createBindingMessageContext(MessageContext srcCtx) {
        return new SOAPMessageContextImpl(srcCtx);
    }

    public HandlerInvoker createHandlerInvoker() {
        return new HandlerChainInvoker(getHandlerChain(true));
    }

    public void marshal(ObjectMessageContext objContext, MessageContext mc, DataBindingCallback callback) {

        try {
            boolean isInputMsg = (Boolean)mc.get(ObjectMessageContext.MESSAGE_INPUT);
            SOAPMessage msg = initSOAPMessage();

            if (null != callback) {

                if (!"".equals(callback.getSOAPAction())) {
                    msg.getMimeHeaders().setHeader("SOAPAction", "\"" + callback.getSOAPAction() + "\"");
                }

                if (callback.getMode() == DataBindingCallback.Mode.PARTS) {
                    if (callback.getSOAPStyle() == Style.RPC) {
                        nsStack = new NSStack();
                        nsStack.push();
                    }

                    // add in, out and inout header params
                    addHeaderParts(msg.getSOAPPart().getEnvelope(), objContext, isInputMsg, callback);

                    SOAPElement soapElement = addOperationNode(msg.getSOAPBody(), callback, isInputMsg);
                    // add in, out and inout non-header params
                    addParts(soapElement, objContext, isInputMsg, callback);
                } else if (callback.getMode() == DataBindingCallback.Mode.MESSAGE) {

                    Object src = isInputMsg ? objContext.getReturn() : objContext.getMessageObjects()[0];
                    // contains the entire SOAP message
                    boolean found = false;
                    for (Class<?> cls : callback.getSupportedFormats()) {
                        if (cls == SOAPMessage.class) {
                            msg = (SOAPMessage)src;
                            found = true;
                            break;
                        } else if (cls == DOMSource.class || cls == SAXSource.class
                                   || cls == StreamSource.class) {
                            DataWriter<SOAPMessage> writer = callback.createWriter(SOAPMessage.class);
                            writer.write(src, msg);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        throw new SOAPException("Could not figure out how to marshal data");
                    }
                } else if (callback.getMode() == DataBindingCallback.Mode.PAYLOAD) {
                    // contains the contents of the SOAP:Body
                    boolean found = false;
                    Object src = isInputMsg ? objContext.getReturn() : objContext.getMessageObjects()[0];

                    for (Class<?> cls : callback.getSupportedFormats()) {
                        if (cls == DOMSource.class || cls == SAXSource.class || cls == StreamSource.class
                            || cls == Object.class) {
                            DataWriter<SOAPBody> writer = callback.createWriter(SOAPBody.class);
                            writer.write(src, msg.getSOAPBody());
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        throw new SOAPException("Could not figure out how to marshal data");
                    }
                }
            } else {
                LOG.fine("Leaving soap message empty - no data binding callback");
            }
            ((SOAPMessageContext)mc).setMessage(msg);
            
            
        } catch (SOAPException se) {
            LOG.log(Level.SEVERE, "SOAP_MARSHALLING_FAILURE_MSG", se);
            throw SOAPFaultExHelper.createSOAPFaultEx(soapFactory, faultCode, se);
        }
    }

    public void marshalFault(ObjectMessageContext objContext, MessageContext mc, 
                             DataBindingCallback callback) {

        SOAPMessage msg = null;

        try {
            msg = SOAPMessageContext.class.isInstance(mc) && ((SOAPMessageContext)mc).getMessage() != null
                ? ((SOAPMessageContext)mc).getMessage() : initSOAPMessage();

            if (msg.getSOAPBody().hasChildNodes()) {
                msg.getSOAPBody().removeContents();
            }

            Throwable t = objContext.getException();
            if (t instanceof SOAPFaultException) {
                msg.getSOAPBody().addChildElement(((SOAPFaultException)t).getFault());
            } else {
                StringBuffer str = new StringBuffer(t.toString());
                if (!t.getClass().isAnnotationPresent(WebFault.class)) {
                    str.append("\n");
                    for (StackTraceElement s : t.getStackTrace()) {
                        str.append(s.toString());
                        str.append("\n");
                    }
                }

                SOAPFault fault = msg.getSOAPBody().addFault(faultCode, str.toString());

                DataWriter<Detail> writer = callback.createWriter(Detail.class);
                if (writer != null) {
                    writer.write(t, fault.addDetail());
                    if (!fault.getDetail().hasChildNodes()) {
                        fault.removeChild(fault.getDetail());
                    }
                }
            }
        } catch (SOAPException se) {
            LOG.log(Level.SEVERE, "FAULT_MARSHALLING_FAILURE_MSG", se);
            // Handle UnChecked Exception, Runtime Exception.
        }
        ((SOAPMessageContext)mc).setMessage(msg);
    }

    public void unmarshal(MessageContext mc, ObjectMessageContext objContext, DataBindingCallback callback) {
        if (null == callback) {
            LOG.fine("Suppress unmarshalling - no data binding callback.");
            return;
        }
        try {
            boolean isOutputMsg = (Boolean)mc.get(ObjectMessageContext.MESSAGE_INPUT);
            if (!SOAPMessageContext.class.isInstance(mc)) {
                throw new SOAPException("SOAPMessageContext not available");
            }

            SOAPMessageContext soapContext = SOAPMessageContext.class.cast(mc);
            SOAPMessage soapMessage = soapContext.getMessage();

            if (callback.getMode() == DataBindingCallback.Mode.PARTS) {
                // Assuming No Headers are inserted.
                Node soapEl = soapMessage.getSOAPBody();

                if (callback.getSOAPStyle() == Style.RPC) {
                    soapEl = NodeUtils.getChildElementNode(soapEl);
                }

                if (soapEl.hasChildNodes()) {
                    getParts(soapEl, callback, objContext, isOutputMsg);
                } else {
                    LOG.fine("Body of SOAP message is empty.");
                }

                getHeaderParts(soapMessage.getSOAPHeader(), callback, objContext, isOutputMsg);
            } else if (callback.getMode() == DataBindingCallback.Mode.MESSAGE) {
                boolean found = false;
                Object obj = null;
                for (Class<?> cls : callback.getSupportedFormats()) {
                    if (cls == SOAPMessage.class) {
                        obj = soapMessage;
                        found = true;
                        break;
                    } else if (cls == DOMSource.class 
                        || cls == SAXSource.class 
                        || cls == StreamSource.class) {
                        DataReader<SOAPMessage> reader = callback.createReader(SOAPMessage.class);
                        obj = reader.read(0, soapMessage);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new SOAPException("Cannot unmarshal data");
                }

                if (isOutputMsg) {
                    objContext.setReturn(obj);
                } else {
                    objContext.setMessageObjects(obj);
                }

            } else if (callback.getMode() == DataBindingCallback.Mode.PAYLOAD) {
                boolean found = false;
                Object obj = null;
                for (Class<?> cls : callback.getSupportedFormats()) {
                    if (cls == DOMSource.class
                        || cls == SAXSource.class 
                        || cls == StreamSource.class
                        || cls == Object.class) {
                        DataReader<SOAPBody> reader = callback.createReader(SOAPBody.class);
                        obj = reader.read(0, soapMessage.getSOAPBody());
                        found = true;
                        break;
                    }
                }
                
                
                
                if (!found) {
                    throw new SOAPException("Cannot unmarshal data");
                }

                if (isOutputMsg) {
                    objContext.setReturn(obj);
                } else {
                    objContext.setMessageObjects(obj);
                }
            }
        } catch (SOAPException se) {
            LOG.log(Level.SEVERE, "SOAP_UNMARSHALLING_FAILURE_MSG", se);
            throw SOAPFaultExHelper.createSOAPFaultEx(soapFactory, faultCode, se);
        }
    }

    public void unmarshalFault(MessageContext context, ObjectMessageContext objContext,
                               DataBindingCallback callback) {
        try {
            if (!SOAPMessageContext.class.isInstance(context)) {
                throw new SOAPException("SOAPMessageContext not available");
            }

            SOAPMessageContext soapContext = SOAPMessageContext.class.cast(context);
            SOAPMessage soapMessage = soapContext.getMessage();

            SOAPFault fault = soapMessage.getSOAPBody().getFault();
            DataReader<SOAPFault> reader = callback.createReader(SOAPFault.class);

            Object faultObj = null;
            if (null != reader) {
                LOG.log(Level.INFO, "SOAP_FAULT_NO_READER");
                faultObj = reader.read(null, 0, fault);
            }
            if (null == faultObj) {
                LOG.log(Level.INFO, "SOAP_FAULT_UNMARSHALLING_MSG", fault.getElementQName().toString());
                faultObj = new SOAPFaultException(fault);
            }
            
            objContext.setException((Throwable)faultObj);
        } catch (SOAPException se) {
            LOG.log(Level.SEVERE, "SOAP_UNMARSHALLING_FAILURE_MSG", se);
            throw SOAPFaultExHelper.createSOAPFaultEx(soapFactory, faultCode, se);
        }
    }

    public void write(MessageContext msgContext, OutputStreamMessageContext outContext) throws IOException {
        SOAPMessageContext soapCtx = (SOAPMessageContext)msgContext;
        try {
            soapCtx.getMessage().writeTo(outContext.getOutputStream());
            if (LOG.isLoggable(Level.FINE)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                soapCtx.getMessage().writeTo(baos);
                LOG.log(Level.FINE, baos.toString());
            }
        } catch (SOAPException se) {
            LOG.log(Level.SEVERE, "SOAP_WRITE_FAILURE_MSG", se);
            throw SOAPFaultExHelper.createSOAPFaultEx(soapFactory, faultCode, se);
        }
    }
    
    @SuppressWarnings("unchecked")
    public void read(InputStreamMessageContext inCtx, MessageContext context) throws IOException {

        if (!SOAPMessageContext.class.isInstance(context)) {
            throw SOAPFaultExHelper.createSOAPFaultEx(soapFactory, faultCode, 
                                                      "SOAPMessageContext not available");
        }
        SOAPMessageContext soapCtx = SOAPMessageContext.class.cast(context);
        SOAPMessage soapMessage;
        QName code = faultCode;
        try {
            MimeHeaders headers = new MimeHeaders();
            Map<String, List<String>> httpHeaders;
            
            if (isServer) {
                httpHeaders = (Map<String, List<String>>)soapCtx.get(MessageContext.HTTP_REQUEST_HEADERS);
            } else {
                httpHeaders = (Map<String, List<String>>)soapCtx.get(MessageContext.HTTP_RESPONSE_HEADERS);
            }
            if (httpHeaders != null) {
                for (String key : httpHeaders.keySet()) {
                    if (null != key) {
                        List<String> values = httpHeaders.get(key);
                        for (String value : values) {
                            headers.addHeader(key, value);
                        }
                    }
                }
            }

            soapMessage = msgFactory.createMessage(headers, inCtx.getInputStream());
            if (LOG.isLoggable(Level.FINE)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                soapMessage.writeTo(baos);
                LOG.log(Level.FINE, baos.toString());
            }
            //Test if it is a valid SOAP 1.1 Message
            code = FAULTCODE_VERSIONMISMATCH;
            soapMessage.getSOAPPart().getEnvelope();
        } catch (SOAPException se) {
            LOG.log(Level.SEVERE, "SOAP_PARSING_FAILURE_MSG", se);
            throw SOAPFaultExHelper.createSOAPFaultEx(soapFactory, code, se);
        }
        
        soapCtx.setMessage(soapMessage);        
    }

    public boolean hasFault(MessageContext msgContext) {
        boolean hasFault = false;
        SOAPMessage msg = ((SOAPMessageContext)msgContext).getMessage();
        assert msg != null;
        try {
            hasFault = msg.getSOAPBody().hasFault();
        } catch (SOAPException se) {
            LOG.log(Level.SEVERE, "SOAP_UNMARSHALLING_FAILURE_MSG", se);
            throw new ProtocolException(se);
        }
        return hasFault;
    }

    public void updateMessageContext(MessageContext msgContext) {
        if (msgContext instanceof SOAPMessageContext) {
            SOAPMessage msg = ((SOAPMessageContext)msgContext).getMessage();
            try {
                updateHeaders(msgContext, msg);
            } catch (SOAPException se) {
                throw SOAPFaultExHelper.createSOAPFaultEx(soapFactory, faultCode, se); 
            }
        }
    }

    // --- Abstr actBindingImpl interface ---

    public Set<String> getRoles() {
        return null;
    }

    public void setRoles(Set<String> set) {
        // TODO
    }

    public boolean isMTOMEnabled() {
        return false;
    }

    public void setMTOMEnabled(boolean flag) {
        throw new WebServiceException("MTOM is not supported");
    }

    public MessageFactory getMessageFactory() {
        return msgFactory;
    }

    @SuppressWarnings("unchecked")
    public void updateHeaders(MessageContext ctx, SOAPMessage msg) throws SOAPException {
        if (msg.saveRequired()) {
            msg.saveChanges();
        }
        MimeHeaders headers = msg.getMimeHeaders();
        Map<String, List<String>> reqHead;
        String inOutKey = MessageContext.HTTP_REQUEST_HEADERS;
        if (isServer) {
            inOutKey = MessageContext.HTTP_RESPONSE_HEADERS;
        }
        reqHead = (Map<String, List<String>>)ctx.get(inOutKey);
        if (reqHead == null) {
            reqHead = new HashMap<String, List<String>>();
            ctx.put(inOutKey, reqHead);
        }
        Iterator it = headers.getAllHeaders();
        while (it.hasNext()) {
            MimeHeader header = (MimeHeader)it.next();
            if (!"Content-Length".equals(header.getName())) {
                List<String> vals = reqHead.get(header.getName());
                if (null == vals) {
                    vals = new ArrayList<String>();
                    reqHead.put(header.getName(), vals);
                }
                vals.add(header.getValue());
            }
        }
    }

    private SOAPElement addOperationNode(SOAPElement body, DataBindingCallback callback, boolean isOutBound)
        throws SOAPException {

        String responseSuffix = isOutBound ? "Response" : "";

        if (callback.getSOAPStyle() == Style.RPC) {
            String namespaceURI = callback.getTargetNamespace();
            nsStack.add(namespaceURI);
            String prefix = nsStack.getPrefix(namespaceURI);
            QName operationName = new QName(namespaceURI, callback.getOperationName() + responseSuffix,
                                            prefix);

            SOAPElement el = body.addChildElement(operationName);
            if (el.lookupPrefix(namespaceURI) == null) {
                el.addNamespaceDeclaration(prefix, namespaceURI);
            }
            return el;
        }
        return body;
    }

    private void getParts(Node xmlNode, DataBindingCallback callback, ObjectMessageContext objCtx,
                          boolean isOutBound) throws SOAPException {

        DataReader<Node> reader = null;
        for (Class<?> cls : callback.getSupportedFormats()) {
            if (cls == Node.class) {
                reader = callback.createReader(Node.class);
                break;
            }
        }

        if (reader == null) {
            throw new SOAPException("Could not figure out how to unmarshal data");
        }

        if (callback.getSOAPStyle() == Style.DOCUMENT
            && callback.getSOAPParameterStyle() == ParameterStyle.WRAPPED) {
            reader.readWrapper(objCtx, isOutBound, xmlNode);
            return;
        }

        Node childNode = NodeUtils.getChildElementNode(xmlNode);
        if (isOutBound && callback.getWebResult() != null && !callback.getWebResult().header()) {

            Object retVal = reader.read(callback.getWebResultQName(), -1, childNode);
            objCtx.setReturn(retVal);
            childNode = childNode.getNextSibling();
        }

        WebParam.Mode ignoreParamMode = isOutBound ? WebParam.Mode.IN : WebParam.Mode.OUT;
        int noArgs = callback.getParamsLength();

        // Unmarshal parts of mode that should notbe ignored and are not part of
        // the SOAP Headers
        Object[] methodArgs = objCtx.getMessageObjects();

        for (int idx = 0; idx < noArgs; idx++) {
            WebParam param = callback.getWebParam(idx);
            if ((param.mode() != ignoreParamMode) && !param.header()) {

                QName elName = (callback.getSOAPStyle() == Style.DOCUMENT) 
                                ? new QName(param.targetNamespace(), param.name()) 
                                : new QName("", param.partName());

                Object obj = reader.read(elName, idx, childNode);
                if (param.mode() != WebParam.Mode.IN) {
                    try {
                        // TO avoid type safety warning the Holder
                        // needs tobe set as below.
                        methodArgs[idx].getClass().getField("value").set(methodArgs[idx], obj);
                    } catch (Exception ex) {
                        throw new SOAPException("Can not set the part value into the Holder field.");
                    }
                } else {
                    methodArgs[idx] = obj;
                }
                childNode = childNode.getNextSibling();
            }
        }
    }

    private void addParts(Node xmlNode, ObjectMessageContext objCtx, boolean isOutBound,
                          DataBindingCallback callback) throws SOAPException {

        DataWriter<Node> writer = null;
        for (Class<?> cls : callback.getSupportedFormats()) {
            if (cls == Node.class) {
                writer = callback.createWriter(Node.class);
                break;
            } else {
                // TODO - other formats to support?
                // StreamSource/DOMSource/STaX/etc..
            }
        }
        if (writer == null) {
            throw new SOAPException("Could not figure out how to marshal data");
        }

        if (callback.getSOAPStyle() == Style.DOCUMENT
            && callback.getSOAPParameterStyle() == ParameterStyle.WRAPPED) {
            writer.writeWrapper(objCtx, isOutBound, xmlNode);
            return;
        }

        // Add the Return Type
        if (isOutBound && callback.getWebResult() != null && !callback.getWebResult().header()) {
            writer.write(objCtx.getReturn(), callback.getWebResultQName(), xmlNode);
        }

        // Add the in,inout,out args depend on the inputMode
        WebParam.Mode ignoreParamMode = isOutBound ? WebParam.Mode.IN : WebParam.Mode.OUT;
        int noArgs = callback.getParamsLength();

        // Marshal parts of mode that should notbe ignored and are not part of
        // the SOAP Headers
        Object[] args = objCtx.getMessageObjects();
        for (int idx = 0; idx < noArgs; idx++) {
            WebParam param = callback.getWebParam(idx);
            if ((param.mode() != ignoreParamMode) && !param.header()) {
                Object partValue = args[idx];
                if (param.mode() != WebParam.Mode.IN) {
                    partValue = ((Holder)args[idx]).value;
                }
                if (param.name().equals("asyncHandler") && idx == (noArgs - 1)) {
                    break;
                }

                QName elName = (callback.getSOAPStyle() == Style.DOCUMENT) 
                                    ? new QName(param.targetNamespace(), param.name()) 
                                    : new QName("", param.partName());
                writer.write(partValue, elName, xmlNode);
            }
        }
    }

    private void getHeaderParts(Element header, DataBindingCallback callback, ObjectMessageContext objCtx,
                                boolean isOutBound) throws SOAPException {

        if (header == null || !header.hasChildNodes()) {
            return;
        }

        DataReader<Node> reader = null;
        for (Class<?> cls : callback.getSupportedFormats()) {
            if (cls == Node.class) {
                reader = callback.createReader(Node.class);
                break;
            } else {
                // TODO - other formats to support?
                // StreamSource/DOMSource/STaX/etc..
            }
        }

        if (reader == null) {
            throw new SOAPException("Could not figure out how to marshal data");
        }

        if (isOutBound && callback.getWebResult() != null && callback.getWebResult().header()) {

            QName elName = callback.getWebResultQName();
            NodeList headerElems = header.getElementsByTagNameNS(elName.getNamespaceURI(), elName
                .getLocalPart());
            assert headerElems.getLength() == 1;
            Node childNode = headerElems.item(0);

            Object retVal = reader.read(elName, -1, childNode);
            objCtx.setReturn(retVal);
        }

        WebParam.Mode ignoreParamMode = isOutBound ? WebParam.Mode.IN : WebParam.Mode.OUT;
        int noArgs = callback.getParamsLength();

        // Unmarshal parts of mode that should notbe ignored and are not part of
        // the SOAP Headers
        Object[] methodArgs = (Object[])objCtx.getMessageObjects();
        for (int idx = 0; idx < noArgs; idx++) {
            WebParam param = callback.getWebParam(idx);
            if ((param.mode() != ignoreParamMode) && param.header()) {
                QName elName = new QName(param.targetNamespace(), param.name());
                NodeList headerElems = header.getElementsByTagNameNS(elName.getNamespaceURI(), elName
                    .getLocalPart());
                assert headerElems.getLength() == 1;
                Node childNode = headerElems.item(0);

                Object obj = reader.read(elName, idx, childNode);
                if (param.mode() != WebParam.Mode.IN) {
                    try {
                        // TO avoid type safety warning the Holder
                        // needs tobe set as below.
                        methodArgs[idx].getClass().getField("value").set(methodArgs[idx], obj);
                    } catch (Exception ex) {
                        throw new SOAPException("Can not set the part value into the Holder field.");
                    }
                } else {
                    methodArgs[idx] = obj;
                }
            }
        }
    }

    private void addHeaderParts(SOAPEnvelope envelope, ObjectMessageContext objCtx, boolean isOutBound,
                                DataBindingCallback callback) throws SOAPException {

        boolean wroteHeader = false;
        DataWriter<Node> writer = null;
        for (Class<?> cls : callback.getSupportedFormats()) {
            if (cls == Node.class) {
                writer = callback.createWriter(Node.class);
                break;
            } else {
                // TODO - other formats to support?
                // StreamSource/DOMSource/STaX/etc..
            }
        }
        if (writer == null) {
            throw new SOAPException("Could not figure out how to marshal data");
        }

        if (isOutBound && callback.getWebResult() != null && callback.getWebResult().header()) {
            SOAPHeader header = envelope.getHeader();
            wroteHeader = true;
            writer.write(objCtx.getReturn(), callback.getWebResultQName(), header);
            addSOAPHeaderAttributes(header, callback.getWebResultQName(), true);
        }

        // Add the in,inout,out args depend on the inputMode
        WebParam.Mode ignoreParamMode = isOutBound ? WebParam.Mode.IN : WebParam.Mode.OUT;
        int noArgs = callback.getParamsLength();

        // Marshal parts of mode that should notbe ignored and are not part of
        // the SOAP Headers
        Object[] args = (Object[])objCtx.getMessageObjects();
        for (int idx = 0; idx < noArgs; idx++) {
            WebParam param = callback.getWebParam(idx);
            if ((param.mode() != ignoreParamMode) && param.header()) {
                SOAPHeader header = envelope.getHeader();
                wroteHeader = true;
                Object partValue = args[idx];
                if (param.mode() != WebParam.Mode.IN) {
                    partValue = ((Holder)args[idx]).value;
                }

                QName elName = new QName(param.targetNamespace(), param.name());
                writer.write(partValue, elName, header);

                addSOAPHeaderAttributes(header, elName, true);
            }
        }
        if (!wroteHeader) {
            envelope.removeChild(envelope.getHeader());
        }
    }

    private void addSOAPHeaderAttributes(SOAPHeader header, QName elName, boolean mustUnderstand) {
        // Set mustUnderstand Attribute on header parts.
        NodeList children = header.getElementsByTagNameNS(elName.getNamespaceURI(), elName.getLocalPart());
        assert children.getLength() == 1;
        // Set the mustUnderstand attribute
        if (children.item(0) instanceof Element) {
            Element child = (Element)(children.item(0));
            String n = header.lookupPrefix(HEADER_MUSTUNDERSTAND.getNamespaceURI());
            n += ":" + HEADER_MUSTUNDERSTAND.getLocalPart();
            child.setAttributeNS(HEADER_MUSTUNDERSTAND.getNamespaceURI(),
                                 HEADER_MUSTUNDERSTAND.getLocalPart(),
                                 mustUnderstand ? "true" : "false");
        }

        // TODO Actor/Role Attribute.
    }

    public SOAPFactory getSOAPFactory() {
        return soapFactory;
    }

    private SOAPMessage initSOAPMessage() throws SOAPException {

        SOAPMessage msg = msgFactory.createMessage();
        msg.setProperty(SOAPMessage.WRITE_XML_DECLARATION, "true");
        msg.getSOAPPart().getEnvelope().addNamespaceDeclaration(W3CConstants.NP_SCHEMA_XSD,
                                                                W3CConstants.NU_SCHEMA_XSD);
        msg.getSOAPPart().getEnvelope().addNamespaceDeclaration(W3CConstants.NP_SCHEMA_XSI,
                                                                W3CConstants.NU_SCHEMA_XSI);

        return msg;
    }
}
