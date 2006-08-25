package org.objectweb.celtix.systest.type_test.soap;


import javax.jws.WebService;
import javax.xml.ws.Endpoint;
//
import javax.xml.ws.Holder;

import org.objectweb.celtix.systest.common.TestServerBase;
import org.objectweb.celtix.systest.type_test.TypeTestImpl;
import org.objectweb.type_test.doc.TypeTestPortType;
//
import org.objectweb.type_test.types.StructWithOccuringChoice;

public class SOAPDocLitServerImpl extends TestServerBase {

    public void run()  {

        Object implementor = new SOAPTypeTestImpl();
        String address = "http://localhost:9007/SOAPService/SOAPPort/";
        Endpoint.publish(address, implementor);
    }

    public static void main(String args[]) {
        try { 
            SOAPDocLitServerImpl s = new SOAPDocLitServerImpl();
            s.start();
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(-1);
        } finally { 
            System.out.println("done!");
        }
    }
    
    @WebService(serviceName = "SOAPDocLitService", portName = "SOAPPort",
                name = "TypeTestPortType",
                targetNamespace = "http://objectweb.org/type_test/doc")
    class SOAPTypeTestImpl extends TypeTestImpl implements TypeTestPortType {

        public StructWithOccuringChoice testStructWithOccuringChoice(
                StructWithOccuringChoice x,
                Holder<StructWithOccuringChoice> y,
                Holder<StructWithOccuringChoice> z) {
            System.out.println("testStructWithOccuringChoice");
            System.out.println("x: " + x.getVarInteger() + " IntOrString list: "
                + x.getVarIntOrVarString().size());
            z.value = y.value;
            y.value = x;
            return x;
        }

    }
}
