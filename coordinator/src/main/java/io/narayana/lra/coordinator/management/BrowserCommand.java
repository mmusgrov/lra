/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package io.narayana.lra.coordinator.management;

import com.arjuna.ats.arjuna.common.CoreEnvironmentBean;
import com.arjuna.ats.arjuna.common.MetaObjectStoreEnvironmentBean;
import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.ats.arjuna.common.recoveryPropertyManager;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.tools.osb.mbean.ObjStoreBrowser;
import com.arjuna.ats.arjuna.tools.osb.util.JMXServer;
import com.arjuna.ats.internal.arjuna.objectstore.hornetq.HornetqJournalEnvironmentBean;
import com.arjuna.ats.internal.arjuna.objectstore.hornetq.HornetqObjectStoreAdaptor;
import com.arjuna.ats.internal.arjuna.objectstore.jdbc.JDBCStore;
import com.arjuna.ats.internal.arjuna.objectstore.jdbc.accessors.DynamicDataSourceJDBCAccess;
import com.arjuna.ats.internal.arjuna.recovery.RecoveryManagerImple;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import io.narayana.lra.coordinator.domain.model.FailedLongRunningAction;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.internal.Implementations;
import io.narayana.lra.coordinator.internal.LRARecoveryModule;
import io.narayana.lra.coordinator.tools.osb.mbean.LRAActionBean;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

/*
 * Browser for viewing LRA MBeans:
 *
 * java -cp target/lra-coordinator-jar-with-dependencies.jar io.narayana.lra.coordinator.management.BrowserCommand
 *     -s src/test/resources/test-store -h <true|false>
 * where -s points to the store directory and -h indicates whether to use the activemq journal based store
 * (in which case the -s option must point to a valid journal store)
 */
public abstract class BrowserCommand {
    private static final String SYNTAX = "syntax: [-s <store location>] | [-f <command file>] [-t <h|f|d>";

    private static String currentStoreLocation;
    private static ObjStoreBrowser osb;
    private static String currentType = "";
    private static List<String> recordTypes = new ArrayList<String>();
    private static InputStream cmdSource;
    private static RecoveryManagerImple recoveryManager;
    private static char storeType = 'f';

    private static String[][] LRA_OSB_TYPES = {
            // osTypeClassName, beanTypeClassName - see com.arjuna.ats.arjuna.tools.osb.mbean.ObjStoreBrowser
            {LongRunningAction.getType().substring(1),
                    LongRunningAction.class.getName(),
                    LRAActionBean.class.getName()},
            {FailedLongRunningAction.getType().substring(1),
                    FailedLongRunningAction.class.getName(),
                    LRAActionBean.class.getName()}
    };

    private enum CommandName {
        HELP("show command options and syntax"),
        SELECT("<type> - start browsing a particular transaction type"),
        STORE_DIR("get/set the location of the object store (set fails)"),
        START(null),
        TYPES("list record types"),
        PROBE("refresh the view of the object store"),
        LS("[type] - list transactions of type type. Use the select command to set the default type"),
        QUIT("exit the browser"),
        EXCEPTION_TRACE("true | false - show full exception traces");

        final String cmdHelp; // is set by the HELP command

        CommandName(String cmdHelp) {
            this.cmdHelp = cmdHelp;
        }
    }

    static BrowserCommand getCommand(CommandName name) {
        return getCommand(name.name());
    }

    static BrowserCommand getCommand(String name) {
        name = name.toUpperCase();

        for (BrowserCommand command : commands) {
            if (command.name.name().startsWith(name))
                return command;
        }

        return getCommand(CommandName.HELP);
    }

    private static void parseArgs(String[] args) throws FileNotFoundException {
        String validOpts = "fst"; // command line options (modeled on the bash getopts command)
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                if (i + 1 >= args.length || args[i].length() != 2 || validOpts.indexOf(args[i].charAt(1)) == -1)
                    throw new IllegalArgumentException(SYNTAX);

                switch (args[i++].charAt(1)) {
                    case 'f': // a file used for reading commands instead of from standard input
                        File f = validateFile(args[i], false);
                        Scanner s = new Scanner(new FileInputStream(f));

                        while (s.hasNext()) {
                            String ln = s.nextLine();

                            sb.append(ln.trim()).append(System.lineSeparator());
                        }

                        break;
                    case 's': // set the location of the file based object store
                        currentStoreLocation = args[i];

                        break;
                    case 't':
                        storeType = args[i].charAt(0);

                        if ("fhd".indexOf(storeType) == -1) {
                            throw new IllegalArgumentException(SYNTAX);
                        }

                        break;
                    default:
                        throw new IllegalArgumentException(SYNTAX);
                }
            }
        }

        if (currentStoreLocation == null)
            currentStoreLocation = BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean.class).getObjectStoreDir();
        else
            BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean.class).setObjectStoreDir(currentStoreLocation);

        if (storeType != 'd') {
            validateFile(currentStoreLocation, true);
        }

        if (cmdSource == null)
            cmdSource = sb.isEmpty() ? System.in : new ByteArrayInputStream(sb.toString().getBytes());
    }

    private static File validateFile(String name, boolean isDir) {
        File f = new File(name);

        if (!f.exists() || !(isDir ^ f.isFile()))
            throw new IllegalArgumentException("File " + name + " does not exist");

        return f;
    }

    private static boolean setCurrentStoreDir(String val) {
        if (val.trim().toUpperCase().startsWith(CommandName.STORE_DIR.name())) {
            String[] aa = val.split("\\s+");

            if (aa.length < 2)
                throw new IllegalArgumentException("Invalid syntax for command " + CommandName.STORE_DIR.name());

            currentStoreLocation = aa[1];

            return true;
        }

        return false;
    }

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        BrowserCommand.getCommand(CommandName.START).execute(new PrintStream(System.out, true), null);
    }

    public static String run(String[] args) throws Exception {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             PrintStream printStream = new PrintStream(outputStream)) {

            parseArgs(args);

            BrowserCommand.getCommand(CommandName.START).execute(printStream, null);

            return outputStream.toString();
        }
    }

    CommandName name;
    boolean verbose;

    private BrowserCommand(CommandName name) {
        this.name = name;
    }

    abstract void execute(PrintStream printStream, List<String> args) throws Exception;

    protected void help(PrintStream printStream) {
        if (name.cmdHelp != null)
            printStream.printf("%s - %s%n", name.name().toLowerCase(), name.cmdHelp);
    }

    boolean cancel() {return true;}

    private static void setupStore(String storeDir) throws Exception {
        recoveryPropertyManager.getRecoveryEnvironmentBean().setRecoveryBackoffPeriod(1);
        Implementations.install();

        // setup the store before starting recovery otherwise recovery won't use the desired store
        initEnvBean(storeDir, storeType);

        recoveryManager = new RecoveryManagerImple(false);
        recoveryManager.addModule(new LRARecoveryModule());

        osb = new ObjStoreBrowser();
        for(String[] typeAndBean: LRA_OSB_TYPES) {
            osb.addType(typeAndBean[0], typeAndBean[1], typeAndBean[2]);
        }
        osb.start();
    }

    private static void initEnvBean(String storeDir, char storeType) throws Exception {
        String storePath = new File(storeDir).getCanonicalPath();
        ObjectStoreEnvironmentBean commsObjStoreCommsEnvBean =
                BeanPopulator.getNamedInstance(ObjectStoreEnvironmentBean.class, "communicationStore");
        ObjectStoreEnvironmentBean defObjStoreCommsEnvBean =
                BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean.class);

        if (storeType == 'h') {
            File hornetqStoreDir = new File(storeDir);
            String storeClassName = HornetqObjectStoreAdaptor.class.getName();

            BeanPopulator.getDefaultInstance(HornetqJournalEnvironmentBean.class)
                    .setStoreDir(hornetqStoreDir.getCanonicalPath());

            defObjStoreCommsEnvBean.setObjectStoreType(storeClassName);
            commsObjStoreCommsEnvBean.setObjectStoreDir(storeDir);

            defObjStoreCommsEnvBean.setObjectStoreType(storeClassName);
            commsObjStoreCommsEnvBean.setObjectStoreType(storeClassName);
        } else if (storeType == 'd') {
            String jdbcStoreClass = JDBCStore.class.getName();
            String jdbcAccess = DynamicDataSourceJDBCAccess.class.getName();
            String DB_CLASSNAME = "org.h2.jdbcx.JdbcDataSource";
            String DB_URL = currentStoreLocation; //"jdbc:h2:file:./h2/JBTMDB";
            String dataSourceJndiName = String.format("%s;ClassName=%s;URL=%s;User=sa;Password=sa",
                    jdbcAccess, DB_CLASSNAME, DB_URL);

            // Narayana uses environment beans to configure the store used to persist transaction logs.
            // Although it uses various stores for persisting different categories of information
            // (the default store, stateStore and communicationStore) there is a bean which propagates
            // config settings to all relevant store config beans:
            final MetaObjectStoreEnvironmentBean metaObjectStoreEnvironmentBean =
                    BeanPopulator.getDefaultInstance(MetaObjectStoreEnvironmentBean.class);

            // set the store type to the JDBC store
            metaObjectStoreEnvironmentBean.setObjectStoreType(jdbcStoreClass);
            // set the JNDI name of the datasource to be  used to access the database
            metaObjectStoreEnvironmentBean.setJdbcAccess(dataSourceJndiName);
        } else {
            defObjStoreCommsEnvBean.setObjectStoreDir(storePath);
            commsObjStoreCommsEnvBean.setObjectStoreDir(storePath);
        }

        BeanPopulator.getDefaultInstance(CoreEnvironmentBean.class).setNodeIdentifier("no-recovery");

        currentStoreLocation = storeDir;
    }

    private static void restartStore(String storeDir) throws Exception {
        StoreManager.shutdown();

        if (osb != null)
            osb.stop();

        try {
            RecoveryManager.manager().terminate(false);
        } catch (Throwable ignore) {
        }

        setupStore(storeDir);
    }

    private static final BrowserCommand[] commands = {

            new BrowserCommand(CommandName.HELP) {
                @Override
                void execute(PrintStream printStream, List<String> args) {
                    for (BrowserCommand command : commands)
                        command.help(printStream);
                }
            },

            new BrowserCommand(CommandName.START) {
                boolean finished;

                @Override
                void execute(PrintStream printStream, List<String> args) throws Exception {
                    setupStore(currentStoreLocation);

                    Scanner scanner = new Scanner(cmdSource);

                    getCommand(CommandName.PROBE).execute(printStream, null);
                    getCommand(CommandName.TYPES).execute(printStream, null);

                    while (!finished)
                        processCommand(printStream, scanner);

                    scanner.close();

                    StoreManager.shutdown();

                    if (osb != null)
                        osb.stop();

                    try {
                        RecoveryManager.manager().terminate(false);
                    } catch (Throwable ignore) {
                    }
                 }

                 boolean cancel() {
                    finished = true;
                    try {
                        cmdSource.close();
                    } catch (IOException ignore) {
                    }
                    return true;
                }

                private void processCommand(PrintStream printStream, Scanner scanner) {
                    printStream.printf("%s> ", currentType);

                    List<String> args = new ArrayList<String> (Arrays.asList(scanner.nextLine().split("\\s+")));
                    BrowserCommand command = args.size() == 0 ? getCommand(CommandName.HELP) : getCommand(args.remove(0));

                    try {
                        command.execute(printStream, args);
                    } catch (Exception e) {
                        printStream.printf("%s%n", e.getMessage());

                        if (verbose)
                            e.printStackTrace(printStream);
                    }
                }
            },

            new BrowserCommand(CommandName.QUIT) {

                @Override
                void execute(PrintStream printStream, List<String> args) throws Exception {
                    getCommand(CommandName.START).cancel();
                    StoreManager.shutdown();
                }
            },

            new BrowserCommand(CommandName.STORE_DIR) {

                @Override
                void execute(PrintStream printStream, List<String> args) throws Exception {
                    if (args.size() == 0)
                        printStream.print(currentStoreLocation);
                    else
                        printStream.printf("not supported - please restart and use the \"-s\" option (%s)", SYNTAX);
//                    restartStore(args.get(0));
                }
            },

            new BrowserCommand(CommandName.PROBE) {

                @Override
                void execute(PrintStream printStream, List<String> args) throws Exception {
                    osb.probe();
                }
            },

            new BrowserCommand(CommandName.EXCEPTION_TRACE) {

                @Override
                void execute(PrintStream printStream, List<String> args) throws Exception {
                    BrowserCommand startCmd = getCommand(CommandName.START);

                    if (args.size() == 1)
                        startCmd.verbose = Boolean.parseBoolean(args.get(0));

                    printStream.printf("exceptionTrace is %b", startCmd.verbose);
                }
            },

            new BrowserCommand(CommandName.TYPES) {
                @Override
                void execute(PrintStream printStream, List<String> args) throws Exception {
                    recordTypes.clear();

                    InputObjectState types = new InputObjectState();

                    if (StoreManager.getRecoveryStore().allTypes(types)) {
                        String typeName;

                        do {
                            try {
                                typeName = types.unpackString();
                                recordTypes.add(typeName);
                                printStream.printf("%s%n", typeName);
                            } catch (IOException e1) {
                                typeName = "";
                            }
                        } while (!typeName.isEmpty());
                    }
                }
            },

            new BrowserCommand(CommandName.SELECT) {

                @Override
                void execute(PrintStream printStream, List<String> args) throws Exception {
                    if (args.size() < 1)
                        currentType = "";
                    else if (!recordTypes.contains(args.get(0)))
                        printStream.printf("%s is not a valid transaction type%n", args.get(0));
                    else
                        currentType = args.get(0);
                }
            },

            new BrowserCommand(CommandName.LS) {
                @Override
                void execute(PrintStream printStream, List<String> args) throws Exception {
                    if (!args.isEmpty())
                        getCommand(CommandName.SELECT).execute(printStream, args);

                    if (currentType.isEmpty()) {
                        for (String type : recordTypes)
                            listMBeans(printStream, type);
                    } else {
                        listMBeans(printStream, currentType);
                    }
                }

                void listMBeans(PrintStream printStream, String itype) throws MalformedObjectNameException, ReflectionException, InstanceNotFoundException, IntrospectionException {
                    MBeanServer mbs = JMXServer.getAgent().getServer();
                    String osMBeanName = "jboss.jta:type=ObjectStore,itype=" + itype;
                    //Set<ObjectInstance> allTransactions = mbs.queryMBeans(new ObjectName("jboss.jta:type=ObjectStore,*"), null);
                    Set<ObjectInstance> transactions = mbs.queryMBeans(new ObjectName(osMBeanName + ",*"), null);

                    printStream.printf("Transactions of type %s%n", osMBeanName);
                    for (ObjectInstance oi : transactions) {
                        String transactionId = oi.getObjectName().getCanonicalName();

                        if (!transactionId.contains("puid") && transactionId.contains("itype")) {
                            printStream.printf("Transaction: %s%n", oi.getObjectName());
                            String participantQuery =  transactionId + ",puid=*";
                            Set<ObjectInstance> participants = mbs.queryMBeans(new ObjectName(participantQuery), null);

                            printAtrributes(printStream, "\t", mbs, oi);

                            printStream.printf("\tParticipants:%n");
                            for (ObjectInstance poi : participants) {
                                printStream.printf("\t\tParticipant: %s%n", poi);
                                printAtrributes(printStream, "\t\t\t", mbs, poi);
                            }
                        }
                    }
                }

                void printAtrributes(PrintStream printStream, String printPrefix, MBeanServer mbs, ObjectInstance oi)
                        throws IntrospectionException, InstanceNotFoundException, ReflectionException {
                    MBeanInfo info = mbs.getMBeanInfo( oi.getObjectName() );
                    MBeanAttributeInfo[] attributeArray = info.getAttributes();
                    int i = 0;
                    String[] attributeNames = new String[attributeArray.length];

                    for (MBeanAttributeInfo ai : attributeArray)
                        attributeNames[i++] = ai.getName();

                    AttributeList attributes = mbs.getAttributes(oi.getObjectName(), attributeNames);

                    for (Attribute attribute : attributes.asList()) {
                        Object value = attribute.getValue();
                        String v =  value == null ? "null" : value.toString();

                        printStream.printf("%s%s=%s%n", printPrefix, attribute.getName(), v);
                    }
                }
            },
    };
}
