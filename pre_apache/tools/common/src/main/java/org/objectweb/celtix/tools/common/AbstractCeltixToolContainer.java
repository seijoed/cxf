package org.objectweb.celtix.tools.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Logger;

import org.objectweb.celtix.common.logging.LogUtils;
import org.objectweb.celtix.tools.common.toolspec.AbstractToolContainer;
import org.objectweb.celtix.tools.common.toolspec.ToolSpec;
import org.objectweb.celtix.tools.common.toolspec.parser.BadUsageException;
import org.objectweb.celtix.tools.common.toolspec.parser.CommandDocument;
import org.objectweb.celtix.tools.common.toolspec.parser.CommandLineParser;
import org.objectweb.celtix.tools.common.toolspec.parser.ErrorVisitor;
import org.objectweb.celtix.version.Version;

public abstract class AbstractCeltixToolContainer extends AbstractToolContainer {
    protected static final Logger LOG = LogUtils.getL7dLogger(AbstractCeltixToolContainer.class);
    private static AbstractCeltixToolContainer instance;
    
    private final String name;
    private CommandDocument commandDocument;
    private boolean verbose;
    private String usage;
    private final ErrorVisitor errors = new ErrorVisitor();


    public AbstractCeltixToolContainer(String nm, ToolSpec toolspec) throws Exception {
        super(toolspec);
        name = nm;
        instance = this;
    }

    public static AbstractCeltixToolContainer getInstance() {
        return instance;
    }
    public boolean hasInfoOption() throws ToolException {
        boolean result = false;
        commandDocument = getCommandDocument();
        if ((commandDocument.hasParameter("help")) || (commandDocument.hasParameter("version"))) {
            result = true;
        }
        return result;
    }

    public void execute(boolean exitOnFinish) throws ToolException {
        if (hasInfoOption()) {
            outputInfo();
        } else {
            if (commandDocument.hasParameter("verbose")) {
                verbose = true;
                outputFullCommandLine();
                outputVersion();
                
            }
            checkParams(errors);
        }             
    }
    
    private void outputInfo() {
        CommandLineParser parser = getCommandLineParser();

        if (commandDocument.hasParameter("help")) {
            try {
                System.out.println(name + " " + getUsage());
                System.out.println();
                System.out.println("Options : ");
                System.out.println();
                System.out.println(parser.getDetailedUsage());
                String toolUsage = parser.getToolUsage();

                if (toolUsage != null) {
                    System.out.println(toolUsage);
                    System.out.println();
                }
            } catch (Exception ex) {
                System.err.println("Error : Could not output detailed usage");
                System.err.println();
            }
        }
        if (commandDocument.hasParameter("version")) {
            outputVersion();
        }
    }

    public abstract void checkParams(ErrorVisitor err) throws ToolException;

    public boolean isVerboseOn() {
        return verbose;
    }

    public String getToolName() {
        return name;
    }

    public String getUsage() {
        if (usage == null) {
            try {
                CommandLineParser parser = getCommandLineParser();

                if (parser != null) {
                    usage = parser.getUsage();
                }
            } catch (Exception ex) {
                usage = "Could not get usage for the tool";
            }
        }
        return usage;
    }

    public void outputVersion() {
        System.out.println(name + " - " + Version.getCompleteVersionString());
        System.out.println();
    }

    public void outputFullCommandLine() {
        System.out.print(name);
        for (int i = 0; i < getArgument().length; i++) {
            System.out.print(" " + getArgument()[i]);
        }
        System.out.println();
    }
    
    public String getFileBase(String wsdlUrl) {
        String fileBase = wsdlUrl;
        StringTokenizer tok = new StringTokenizer(wsdlUrl, "\\/");

        while (tok.hasMoreTokens()) {
            fileBase = tok.nextToken();
        }
        if (fileBase.endsWith(".wsdl")) {
            fileBase = new String(fileBase.substring(0, fileBase.length() - 5));
        }
        return fileBase;
    }

    public void printUsageException(String toolName, BadUsageException ex) {
        if (getInstance().verbose) {
            getInstance().outputFullCommandLine();
        }
        System.err.println(ex.getMessage());
        System.err.println("Usage : " + toolName + " " + ex.getUsage());
        if (getInstance().verbose) {
            getInstance().outputVersion();
        }
        System.err.println();
    }

    public String getFileName(String loc) {
        int idx = loc.lastIndexOf("/");

        if (idx != -1) {
            loc = loc.substring(idx + 1);
        }
        idx = loc.lastIndexOf("\\");
        if (idx != -1) {
            loc = loc.substring(idx + 1);
        }

        idx = loc.lastIndexOf(".");
        if (idx != -1) {
            loc = loc.substring(0, idx);
        }

        StringTokenizer strToken = new StringTokenizer(loc, "-.!~*'();?:@&=+$,");
        StringBuffer strBuf = new StringBuffer();

        if (!strToken.hasMoreTokens()) {
            strBuf.append(loc);
        }

        while (strToken.hasMoreTokens()) {
            strBuf.append(strToken.nextToken());
            if (strToken.countTokens() != 0) {
                strBuf.append("_");
            }
        }

        return strBuf.toString();
    }

    private InputStream getResourceAsStream(String resource) {
        ClassLoader cl = AbstractCeltixToolContainer.class.getClassLoader();
        InputStream ins = cl.getResourceAsStream(resource);
        if (ins == null && resource.startsWith("/")) {
            ins = cl.getResourceAsStream(resource.substring(1));
        }
        return ins;
    }
   
    public Properties loadProperties(InputStream inputs) {
        Properties p = new Properties();
        try {
            p.load(inputs);
            inputs.close();
        } catch (IOException ex) {
            // ignore, use defaults
        }
        return p;
    }
    
    
    public Properties loadProperties(String propertyFile) {
        Properties p = new Properties();

        try {
            InputStream ins = getResourceAsStream(propertyFile);

            p.load(ins);
            ins.close();
        } catch (IOException ex) {
            // ignore, use defaults
        }
        return p;
    }

    protected String[] getDefaultExcludedNamespaces(String excludeProps) {
        List<String> result = new ArrayList<String>();
        Properties props = loadProperties(excludeProps);
        java.util.Enumeration nexcludes = props.propertyNames();

        while (nexcludes.hasMoreElements()) {
            result.add(props.getProperty((String)nexcludes.nextElement()));
        }
        return result.toArray(new String[result.size()]);
    }

    /**
     * get all parameters in a map
     * @param stringArrayKeys, contains keys, whose value should be string array
     */
    protected Map<String, Object> getParametersMap(Set stringArrayKeys) {
        Map<String, Object> map = new HashMap<String, Object>();
        CommandDocument doc = getCommandDocument();
        String[] keys = doc.getParameterNames();
        if (keys == null) {
            return map;
        }
        for (int i = 0; i < keys.length; i++) {
            if (stringArrayKeys.contains(keys[i])) {
                map.put(keys[i], doc.getParameters(keys[i]));
            } else {
                map.put(keys[i], doc.getParameter(keys[i]));
            }
        }
        return map;
    }
}
