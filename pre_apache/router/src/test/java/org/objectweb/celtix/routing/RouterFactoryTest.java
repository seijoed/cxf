package org.objectweb.celtix.routing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.wsdl.Definition;

import junit.framework.TestCase;
import org.objectweb.celtix.Bus;
import org.objectweb.celtix.routing.configuration.RouteType;

public class RouterFactoryTest extends TestCase {
    
    private Map<String, Object> properties;
    public void setUp() {
        properties = new HashMap<String, Object>();
    }

    public void tearDown() throws Exception {
        Bus bus = Bus.getCurrent();
        bus.shutdown(true);
        Bus.setCurrent(null);
    }
    
    public void testAddRoutes() throws Exception {
        properties.put("org.objectweb.celtix.BusId", "celtix1");
        Bus bus = Bus.init(null, properties);
        Bus.setCurrent(bus);
        
        TestRouterFactory factory = new TestRouterFactory();
        factory.init(bus);
        
        Definition def = bus.getWSDLManager().getDefinition(getClass().getResource("resources/router.wsdl"));

        List<Router> rList = factory.addRoutes(def);
        assertEquals(4, rList.size());
        assertEquals(4, factory.routerCount);
    }
    
    public static void main(String[] args) {
        junit.textui.TestRunner.run(RouterFactoryTest.class);
    }
    
    
    class TestRouterFactory extends RouterFactory {
        private int routerCount;
        public TestRouterFactory() {
            super(null);
            routerCount = 0;
        }
        
        public Router createRouter(Definition model, RouteType route) {
            //Router router = super.createRouter(model, route);
            ++routerCount;
            return new TestRouter();
        }
    }
    
    class TestRouter implements Router {
        public TestRouter() {
            //Complete
        }
        
        public Definition getWSDLModel() {
            return null;
        }

        public RouteType getRoute() {
            return null;
        }

        public void init() {
            //Complete
        }

        public void publish() {
            //Complete
        }
    }
}
