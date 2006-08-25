package org.objectweb.celtix.jaxb.io;

import javax.xml.namespace.QName;

import org.w3c.dom.Node;

import org.objectweb.celtix.bindings.DataWriter;
import org.objectweb.celtix.context.ObjectMessageContext;
import org.objectweb.celtix.jaxb.JAXBDataBindingCallback;
import org.objectweb.celtix.jaxb.JAXBEncoderDecoder;

public class NodeDataWriter<T> implements DataWriter<T> {
    final JAXBDataBindingCallback callback;
    
    public NodeDataWriter(JAXBDataBindingCallback cb) {
        callback = cb;
    }
    public void write(Object obj, T output) {
        write(obj, null, output);
    }
    public void write(Object obj, QName elName, T output) {
        if (obj != null) {
            JAXBEncoderDecoder.marshall(callback.getJAXBContext(),
                callback.getSchema(), obj, elName, (Node)output);
        }
    }
    public void writeWrapper(ObjectMessageContext objCtx, boolean isOutBound, T output) {
        Object obj = callback.createWrapperType(objCtx, isOutBound);
        QName elName = isOutBound ? callback.getResponseWrapperQName()
            : callback.getRequestWrapperQName();
        write(obj, elName, output);
    }
}
