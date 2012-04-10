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

package org.apache.cxf.ws.rm.persistence.jdbc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;

import org.apache.cxf.common.i18n.Message;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.common.util.SystemPropertyAction;
import org.apache.cxf.ws.addressing.EndpointReferenceType;
import org.apache.cxf.ws.rm.DestinationSequence;
import org.apache.cxf.ws.rm.ProtocolVariation;
import org.apache.cxf.ws.rm.RMUtils;
import org.apache.cxf.ws.rm.SourceSequence;
import org.apache.cxf.ws.rm.persistence.PersistenceUtils;
import org.apache.cxf.ws.rm.persistence.RMMessage;
import org.apache.cxf.ws.rm.persistence.RMStore;
import org.apache.cxf.ws.rm.persistence.RMStoreException;
import org.apache.cxf.ws.rm.v200702.Identifier;
import org.apache.cxf.ws.rm.v200702.SequenceAcknowledgement;

public class RMTxStore implements RMStore {
    
    public static final String DEFAULT_DATABASE_NAME = "rmdb";
    private static final String[][] DEST_SEQUENCES_TABLE_COLS 
        = {{"SEQ_ID", "VARCHAR(256) NOT NULL"},
           {"ACKS_TO", "VARCHAR(1024) NOT NULL"},
           {"LAST_MSG_NO", "DECIMAL(19, 0)"},
           {"ENDPOINT_ID", "VARCHAR(1024)"},
           {"ACKNOWLEDGED", "BLOB"},
           {"PROTOCOL_VERSION", "VARCHAR(256)"}};
    private static final String[] DEST_SEQUENCES_TABLE_KEYS = {"SEQ_ID"};
    private static final String[][] SRC_SEQUENCES_TABLE_COLS
        = {{"SEQ_ID", "VARCHAR(256) NOT NULL"},
           {"CUR_MSG_NO", "DECIMAL(19, 0) DEFAULT 1 NOT NULL"},
           {"LAST_MSG", "CHAR(1)"},
           {"EXPIRY", "DECIMAL(19, 0)"},
           {"OFFERING_SEQ_ID", "VARCHAR(256)"},
           {"ENDPOINT_ID", "VARCHAR(1024)"},
           {"PROTOCOL_VERSION", "VARCHAR(256)"}};
    private static final String[] SRC_SEQUENCES_TABLE_KEYS = {"SEQ_ID"};
    private static final String[][] MESSAGES_TABLE_COLS
        = {{"SEQ_ID", "VARCHAR(256) NOT NULL"},
           {"MSG_NO", "DECIMAL(19, 0) NOT NULL"},
           {"SEND_TO", "VARCHAR(256)"},
           {"CONTENT", "BLOB"}};
    private static final String[] MESSAGES_TABLE_KEYS = {"SEQ_ID", "MSG_NO"};
    

    private static final String DEST_SEQUENCES_TABLE_NAME = "CXF_RM_DEST_SEQUENCES"; 
    private static final String SRC_SEQUENCES_TABLE_NAME = "CXF_RM_SRC_SEQUENCES";
    private static final String INBOUND_MSGS_TABLE_NAME = "CXF_RM_INBOUND_MESSAGES";
    private static final String OUTBOUND_MSGS_TABLE_NAME = "CXF_RM_OUTBOUND_MESSAGES";    
    
    private static final String CREATE_DEST_SEQUENCES_TABLE_STMT = 
        buildCreateTableStatement(DEST_SEQUENCES_TABLE_NAME, 
                                  DEST_SEQUENCES_TABLE_COLS, DEST_SEQUENCES_TABLE_KEYS);

    private static final String CREATE_SRC_SEQUENCES_TABLE_STMT =
        buildCreateTableStatement(SRC_SEQUENCES_TABLE_NAME, 
                                  SRC_SEQUENCES_TABLE_COLS, SRC_SEQUENCES_TABLE_KEYS);
    private static final String CREATE_MESSAGES_TABLE_STMT =
        buildCreateTableStatement("{0}", 
                                  MESSAGES_TABLE_COLS, MESSAGES_TABLE_KEYS);

    private static final String CREATE_DEST_SEQUENCE_STMT_STR 
        = "INSERT INTO CXF_RM_DEST_SEQUENCES (SEQ_ID, ACKS_TO, ENDPOINT_ID, PROTOCOL_VERSION) " 
            + "VALUES(?, ?, ?, ?)";
    private static final String CREATE_SRC_SEQUENCE_STMT_STR
        = "INSERT INTO CXF_RM_SRC_SEQUENCES VALUES(?, 1, '0', ?, ?, ?, ?)";
    private static final String DELETE_DEST_SEQUENCE_STMT_STR =
        "DELETE FROM CXF_RM_DEST_SEQUENCES WHERE SEQ_ID = ?";
    private static final String DELETE_SRC_SEQUENCE_STMT_STR =
        "DELETE FROM CXF_RM_SRC_SEQUENCES WHERE SEQ_ID = ?";
    private static final String UPDATE_DEST_SEQUENCE_STMT_STR =
        "UPDATE CXF_RM_DEST_SEQUENCES SET LAST_MSG_NO = ?, ACKNOWLEDGED = ? WHERE SEQ_ID = ?";
    private static final String UPDATE_SRC_SEQUENCE_STMT_STR =
        "UPDATE CXF_RM_SRC_SEQUENCES SET CUR_MSG_NO = ?, LAST_MSG = ? WHERE SEQ_ID = ?";
    private static final String CREATE_MESSAGE_STMT_STR 
        = "INSERT INTO {0} VALUES(?, ?, ?, ?)";
    private static final String DELETE_MESSAGE_STMT_STR =
        "DELETE FROM {0} WHERE SEQ_ID = ? AND MSG_NO = ?";
    private static final String SELECT_DEST_SEQUENCE_STMT_STR =
        "SELECT ACKS_TO, LAST_MSG_NO, PROTOCOL_VERSION, ACKNOWLEDGED FROM CXF_RM_DEST_SEQUENCES "
        + "WHERE SEQ_ID = ?";
    private static final String SELECT_SRC_SEQUENCE_STMT_STR =
        "SELECT CUR_MSG_NO, LAST_MSG, EXPIRY, OFFERING_SEQ_ID, PROTOCOL_VERSION FROM CXF_RM_SRC_SEQUENCES "
        + "WHERE SEQ_ID = ?";
    private static final String SELECT_DEST_SEQUENCES_STMT_STR =
        "SELECT SEQ_ID, ACKS_TO, LAST_MSG_NO, PROTOCOL_VERSION, ACKNOWLEDGED FROM CXF_RM_DEST_SEQUENCES "
        + "WHERE ENDPOINT_ID = ?";
    private static final String SELECT_SRC_SEQUENCES_STMT_STR =
        "SELECT SEQ_ID, CUR_MSG_NO, LAST_MSG, EXPIRY, OFFERING_SEQ_ID, PROTOCOL_VERSION "
        + "FROM CXF_RM_SRC_SEQUENCES WHERE ENDPOINT_ID = ?";
    private static final String SELECT_MESSAGES_STMT_STR =
        "SELECT MSG_NO, SEND_TO, CONTENT FROM {0} WHERE SEQ_ID = ?";
    private static final String ALTER_TABLE_STMT_STR =
        "ALTER TABLE {0} ADD {1} {2}";
    private static final String DERBY_TABLE_EXISTS_STATE = "X0Y32";
    private static final int ORACLE_TABLE_EXISTS_CODE = 955;
    
    private static final Logger LOG = LogUtils.getL7dLogger(RMTxStore.class);
    
    private Connection connection;
    private Lock writeLock = new ReentrantLock();
    
    private PreparedStatement createDestSequenceStmt;
    private PreparedStatement createSrcSequenceStmt;
    private PreparedStatement deleteDestSequenceStmt;
    private PreparedStatement deleteSrcSequenceStmt;
    private PreparedStatement updateDestSequenceStmt;
    private PreparedStatement updateSrcSequenceStmt;
    private PreparedStatement selectDestSequencesStmt;
    private PreparedStatement selectSrcSequencesStmt;
    private PreparedStatement selectDestSequenceStmt;
    private PreparedStatement selectSrcSequenceStmt;
    private PreparedStatement createInboundMessageStmt;
    private PreparedStatement createOutboundMessageStmt;
    private PreparedStatement deleteInboundMessageStmt;
    private PreparedStatement deleteOutboundMessageStmt;
    private PreparedStatement selectInboundMessagesStmt;
    private PreparedStatement selectOutboundMessagesStmt;
    
    private String driverClassName = "org.apache.derby.jdbc.EmbeddedDriver";
    private String url = MessageFormat.format("jdbc:derby:{0};create=true", DEFAULT_DATABASE_NAME);
    private String userName;
    private String password;
    
    private String tableExistsState = DERBY_TABLE_EXISTS_STATE;
    private int tableExistsCode = ORACLE_TABLE_EXISTS_CODE;
    
    // configuration
    
    public void setDriverClassName(String dcn) {
        driverClassName = dcn;
    }
    
    public String getDriverClassName() {
        return driverClassName;
    }
    
    public void setPassword(String p) {
        password = p;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setUrl(String u) {
        url = u;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUserName(String un) {
        userName = un;
    }
    
    public String getUserName() {
        return userName;
    }
    
    public String getTableExistsState() {
        return tableExistsState;
    }

    public void setTableExistsState(String tableExistsState) {
        this.tableExistsState = tableExistsState;
    }

    public int getTableExistsCode() {
        return tableExistsCode;
    }

    public void setTableExistsCode(int tableExistsCode) {
        this.tableExistsCode = tableExistsCode;
    }

    public void setConnection(Connection c) {
        connection = c;
    }
    
    // RMStore interface  
    
    public void createDestinationSequence(DestinationSequence seq) {
        String sequenceIdentifier = seq.getIdentifier().getValue();
        String endpointIdentifier = seq.getEndpointIdentifier();
        String protocolVersion = encodeProtocolVersion(seq.getProtocol());
        if (LOG.isLoggable(Level.FINE)) {
            LOG.info("Creating destination sequence: " + sequenceIdentifier + ", (endpoint: "
                 + endpointIdentifier + ")");
        }
        try {
            beginTransaction();
            
            if (null == createDestSequenceStmt) {
                createDestSequenceStmt = connection.prepareStatement(CREATE_DEST_SEQUENCE_STMT_STR);
            }
            createDestSequenceStmt.setString(1, sequenceIdentifier);
            String addr = seq.getAcksTo().getAddress().getValue();
            createDestSequenceStmt.setString(2, addr);
            createDestSequenceStmt.setString(3, endpointIdentifier);
            createDestSequenceStmt.setString(4, protocolVersion);
            createDestSequenceStmt.execute();
            
            commit();
            
        } catch (SQLException ex) {
            abort();
            throw new RMStoreException(ex);
        }
    }
    
    public void createSourceSequence(SourceSequence seq) {
        String sequenceIdentifier = seq.getIdentifier().getValue();
        String endpointIdentifier = seq.getEndpointIdentifier();
        String protocolVersion = encodeProtocolVersion(seq.getProtocol());
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Creating source sequence: " + sequenceIdentifier + ", (endpoint: "
                     + endpointIdentifier + ")"); 
        }
        
        try {
            beginTransaction();
            
            if (null == createSrcSequenceStmt) {
                createSrcSequenceStmt = connection.prepareStatement(CREATE_SRC_SEQUENCE_STMT_STR);
            }
            assert null != createSrcSequenceStmt;
            createSrcSequenceStmt.setString(1, sequenceIdentifier);
            Date expiry = seq.getExpires();
            createSrcSequenceStmt.setLong(2, expiry == null ? 0 : expiry.getTime());
            Identifier osid = seq.getOfferingSequenceIdentifier();
            createSrcSequenceStmt.setString(3, osid == null ? null : osid.getValue());
            createSrcSequenceStmt.setString(4, endpointIdentifier);
            createSrcSequenceStmt.setString(5, protocolVersion);
            createSrcSequenceStmt.execute();    
            
            commit();
            
        } catch (SQLException ex) {
            abort();
            throw new RMStoreException(ex);
        }
    }

    public DestinationSequence getDestinationSequence(Identifier sid) {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.info("Getting destination sequence for id: " + sid);
        }
        try {
            if (null == selectDestSequenceStmt) {
                selectDestSequenceStmt = 
                    connection.prepareStatement(SELECT_DEST_SEQUENCE_STMT_STR);               
            }
            selectDestSequenceStmt.setString(1, sid.getValue());
            ResultSet res = selectDestSequenceStmt.executeQuery(); 
            if (res.next()) {
                EndpointReferenceType acksTo = RMUtils.createReference(res.getString(1));  
                long lm = res.getLong(2);
                ProtocolVariation pv = decodeProtocolVersion(res.getString(3));
                InputStream is = res.getBinaryStream(4);
                SequenceAcknowledgement ack = null;
                if (null != is) {
                    ack = PersistenceUtils.getInstance()
                        .deserialiseAcknowledgment(is); 
                }
                return new DestinationSequence(sid, acksTo, lm, ack, pv);
            }
        } catch (SQLException ex) {
            LOG.log(Level.WARNING, new Message("SELECT_DEST_SEQ_FAILED_MSG", LOG).toString(), ex);
        }
        return null;
    }

    public SourceSequence getSourceSequence(Identifier sid) {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.info("Getting source sequences for id: " + sid);
        }
        try {
            if (null == selectSrcSequenceStmt) {
                selectSrcSequenceStmt = 
                    connection.prepareStatement(SELECT_SRC_SEQUENCE_STMT_STR);     
            }
            selectSrcSequenceStmt.setString(1, sid.getValue());
            ResultSet res = selectSrcSequenceStmt.executeQuery();
            
            if (res.next()) {
                long cmn = res.getLong(1);
                boolean lm = res.getBoolean(2);
                long lval = res.getLong(3);
                Date expiry = 0 == lval ? null : new Date(lval);
                String oidValue = res.getString(4);
                Identifier oi = null;
                if (null != oidValue) {
                    oi = RMUtils.getWSRMFactory().createIdentifier();
                    oi.setValue(oidValue);
                }
                ProtocolVariation pv = decodeProtocolVersion(res.getString(5));
                return new SourceSequence(sid, expiry, oi, cmn, lm, pv);
                          
            }
        } catch (SQLException ex) {
            // ignore
            LOG.log(Level.WARNING, new Message("SELECT_SRC_SEQ_FAILED_MSG", LOG).toString(), ex);
        }
        return null;
    }

    public void removeDestinationSequence(Identifier sid) {
        try {
            beginTransaction();
            
            if (null == deleteDestSequenceStmt) {
                deleteDestSequenceStmt = connection.prepareStatement(DELETE_DEST_SEQUENCE_STMT_STR);
            }
            deleteDestSequenceStmt.setString(1, sid.getValue());
            deleteDestSequenceStmt.execute();
            
            commit();
            
        } catch (SQLException ex) {
            abort();
            throw new RMStoreException(ex);
        }        
    }
    
    
    public void removeSourceSequence(Identifier sid) {
        try {
            beginTransaction();
            
            if (null == deleteSrcSequenceStmt) {
                deleteSrcSequenceStmt = connection.prepareStatement(DELETE_SRC_SEQUENCE_STMT_STR);
            }
            deleteSrcSequenceStmt.setString(1, sid.getValue());
            deleteSrcSequenceStmt.execute();
            
            commit();
            
        } catch (SQLException ex) {
            abort();
            throw new RMStoreException(ex);
        }        
    }
    
    public Collection<DestinationSequence> getDestinationSequences(String endpointIdentifier) {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.info("Getting destination sequences for endpoint: " + endpointIdentifier);
        }
        Collection<DestinationSequence> seqs = new ArrayList<DestinationSequence>();
        try {
            if (null == selectDestSequencesStmt) {
                selectDestSequencesStmt = 
                    connection.prepareStatement(SELECT_DEST_SEQUENCES_STMT_STR);               
            }
            selectDestSequencesStmt.setString(1, endpointIdentifier);
            
            ResultSet res = selectDestSequencesStmt.executeQuery(); 
            while (res.next()) {
                Identifier sid = new Identifier();                
                sid.setValue(res.getString(1));
                EndpointReferenceType acksTo = RMUtils.createReference(res.getString(2));  
                long lm = res.getLong(3);
                ProtocolVariation pv = decodeProtocolVersion(res.getString(4));
                InputStream is = res.getBinaryStream(5);
                SequenceAcknowledgement ack = null;
                if (null != is) {
                    ack = PersistenceUtils.getInstance()
                        .deserialiseAcknowledgment(is); 
                }
                DestinationSequence seq = new DestinationSequence(sid, acksTo, lm, ack, pv);
                seqs.add(seq);                                                 
            }
        } catch (SQLException ex) {
            LOG.log(Level.WARNING, new Message("SELECT_DEST_SEQ_FAILED_MSG", LOG).toString(), ex);
        }
        return seqs;
    }
    
    public Collection<SourceSequence> getSourceSequences(String endpointIdentifier) {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.info("Getting source sequences for endpoint: " + endpointIdentifier);
        }
        Collection<SourceSequence> seqs = new ArrayList<SourceSequence>();
        try {
            if (null == selectSrcSequencesStmt) {
                selectSrcSequencesStmt = 
                    connection.prepareStatement(SELECT_SRC_SEQUENCES_STMT_STR);     
            }
            selectSrcSequencesStmt.setString(1, endpointIdentifier);
            ResultSet res = selectSrcSequencesStmt.executeQuery();
            
            while (res.next()) {
                Identifier sid = new Identifier();
                sid.setValue(res.getString(1));
                long cmn = res.getLong(2);
                boolean lm = res.getBoolean(3);
                long lval = res.getLong(4);
                Date expiry = 0 == lval ? null : new Date(lval);
                String oidValue = res.getString(5);
                Identifier oi = null;
                if (null != oidValue) {
                    oi = new Identifier();
                    oi.setValue(oidValue);
                }
                ProtocolVariation pv = decodeProtocolVersion(res.getString(6));
                SourceSequence seq = new SourceSequence(sid, expiry, oi, cmn, lm, pv);
                seqs.add(seq);                          
            }
        } catch (SQLException ex) {
            // ignore
            LOG.log(Level.WARNING, new Message("SELECT_SRC_SEQ_FAILED_MSG", LOG).toString(), ex);
        }
        return seqs;
    }
    
    public Collection<RMMessage> getMessages(Identifier sid, boolean outbound) {
        Collection<RMMessage> msgs = new ArrayList<RMMessage>();
        try {
            PreparedStatement stmt = outbound ? selectOutboundMessagesStmt : selectInboundMessagesStmt;
            if (null == stmt) {
                stmt = connection.prepareStatement(MessageFormat.format(SELECT_MESSAGES_STMT_STR,
                    outbound ? OUTBOUND_MSGS_TABLE_NAME : INBOUND_MSGS_TABLE_NAME));
                if (outbound) {
                    selectOutboundMessagesStmt = stmt;                    
                } else {
                    selectInboundMessagesStmt = stmt;
                }
            }
            stmt.setString(1, sid.getValue());
            ResultSet res = stmt.executeQuery();
            while (res.next()) {
                long mn = res.getLong(1);
                String to = res.getString(2);
                Blob blob = res.getBlob(3);
                RMMessage msg = new RMMessage();
                msg.setMessageNumber(mn);
                msg.setTo(to);
                msg.setContent(blob.getBinaryStream());
                msgs.add(msg);                
            }            
        } catch (Exception ex) {
            LOG.log(Level.WARNING, new Message(outbound ? "SELECT_OUTBOUND_MSGS_FAILED_MSG"
                : "SELECT_INBOUND_MSGS_FAILED_MSG", LOG).toString(), ex);
        }
        return msgs;
    }
    
    public void persistIncoming(DestinationSequence seq, RMMessage msg) {        
        try {
            beginTransaction();
            
            updateDestinationSequence(seq);
            
            if (msg != null && msg.getCachedOutputStream() != null) {
                storeMessage(seq.getIdentifier(), msg, false);
            }
            
            commit();
            
        } catch (SQLException ex) {
            abort();
            throw new RMStoreException(ex);
        } catch (IOException ex) {
            abort();
            throw new RMStoreException(ex);        
        }        
    }
    public void persistOutgoing(SourceSequence seq, RMMessage msg) {
        try {
            beginTransaction();
            
            updateSourceSequence(seq);
            
            if (msg != null && msg.getCachedOutputStream() != null) {
                storeMessage(seq.getIdentifier(), msg, true);
            }
            
            commit();
            
        } catch (SQLException ex) {
            abort();
            throw new RMStoreException(ex);
        } catch (IOException ex) {
            abort();
            throw new RMStoreException(ex);        
        }        
    }
    
    public void removeMessages(Identifier sid, Collection<Long> messageNrs, boolean outbound) {
        try {
            beginTransaction();
            PreparedStatement stmt = outbound ? deleteOutboundMessageStmt : deleteInboundMessageStmt;
            if (null == stmt) {
                stmt = connection.prepareStatement(MessageFormat.format(DELETE_MESSAGE_STMT_STR,
                    outbound ? OUTBOUND_MSGS_TABLE_NAME : INBOUND_MSGS_TABLE_NAME));
                if (outbound) {
                    deleteOutboundMessageStmt = stmt;                    
                } else {
                    deleteInboundMessageStmt = stmt;
                }
            }
    
            stmt.setString(1, sid.getValue());
                        
            for (Long messageNr : messageNrs) {
                stmt.setLong(2, messageNr);
                stmt.execute();
            }
            
            commit();
            
        } catch (SQLException ex) {
            abort();
            throw new RMStoreException(ex);
        }        
    }
    
    // transaction demarcation
    // 
    
    protected void beginTransaction() {
        // avoid sharing of statements and result sets
        writeLock.lock();
    }
    
    protected void commit() throws SQLException {
        try {
            connection.commit();
        } finally {
            writeLock.unlock();
        }
    }
    
    protected void abort() {
        try {
            connection.rollback(); 
        } catch (SQLException ex) {
            LogUtils.log(LOG, Level.SEVERE, "ABORT_FAILED_MSG", ex);
        } finally {
            writeLock.unlock();
        }
    }
    
    // helpers
    
    protected void storeMessage(Identifier sid, RMMessage msg, boolean outbound)         
        throws IOException, SQLException {
        String id = sid.getValue();
        long nr = msg.getMessageNumber();
        String to = msg.getTo();
        if (LOG.isLoggable(Level.FINE)) {
            LOG.log(Level.FINE, "Storing {0} message number {1} for sequence {2}, to = {3}",
                    new Object[] {outbound ? "outbound" : "inbound", nr, id, to});
        }
        PreparedStatement stmt = outbound ? createOutboundMessageStmt : createInboundMessageStmt;
        if (null == stmt) {
            stmt = connection.prepareStatement(MessageFormat.format(CREATE_MESSAGE_STMT_STR,
                outbound ? OUTBOUND_MSGS_TABLE_NAME : INBOUND_MSGS_TABLE_NAME));
            if (outbound) {
                createOutboundMessageStmt = stmt;                    
            } else {
                createInboundMessageStmt = stmt;
            }
        }
        int i = 1;
        stmt.setString(i++, id);  
        stmt.setLong(i++, nr);
        stmt.setString(i++, to); 
        stmt.setBinaryStream(i++, msg.getInputStream(), (int)msg.getSize());
        stmt.execute();
        if (LOG.isLoggable(Level.FINE)) {
            LOG.log(Level.FINE, "Successfully stored {0} message number {1} for sequence {2}",
                    new Object[] {outbound ? "outbound" : "inbound", nr, id});
        }
        
    }
    
    protected void updateSourceSequence(SourceSequence seq) 
        throws SQLException {
        if (null == updateSrcSequenceStmt) {
            updateSrcSequenceStmt = connection.prepareStatement(UPDATE_SRC_SEQUENCE_STMT_STR);
        }
        updateSrcSequenceStmt.setLong(1, seq.getCurrentMessageNr()); 
        updateSrcSequenceStmt.setString(2, seq.isLastMessage() ? "1" : "0"); 
        updateSrcSequenceStmt.setString(3, seq.getIdentifier().getValue());
        updateSrcSequenceStmt.execute();
    }
    
    protected void updateDestinationSequence(DestinationSequence seq) 
        throws SQLException, IOException {
        if (null == updateDestSequenceStmt) {
            updateDestSequenceStmt = connection.prepareStatement(UPDATE_DEST_SEQUENCE_STMT_STR);
        }
        long lastMessageNr = seq.getLastMessageNumber();
        updateDestSequenceStmt.setLong(1, lastMessageNr); 
        InputStream is = PersistenceUtils.getInstance()
            .serialiseAcknowledgment(seq.getAcknowledgment());
        updateDestSequenceStmt.setBinaryStream(2, is, is.available()); 
        updateDestSequenceStmt.setString(3, seq.getIdentifier() .getValue());
        updateDestSequenceStmt.execute();
    }
    
    protected void createTables() throws SQLException {
        
        Statement stmt = null;
        stmt = connection.createStatement();
        try {
            stmt.executeUpdate(CREATE_SRC_SEQUENCES_TABLE_STMT);
        } catch (SQLException ex) {
            if (!isTableExistsError(ex)) {
                throw ex;
            } else {
                LOG.fine("Table CXF_RM_SRC_SEQUENCES already exists.");
                verifyTable(SRC_SEQUENCES_TABLE_NAME, SRC_SEQUENCES_TABLE_COLS);
            }
        }
        stmt.close();

        stmt = connection.createStatement();
        try {
            stmt.executeUpdate(CREATE_DEST_SEQUENCES_TABLE_STMT);
        } catch (SQLException ex) {
            if (!isTableExistsError(ex)) {
                throw ex;
            } else {
                LOG.fine("Table CXF_RM_DEST_SEQUENCES already exists.");
                verifyTable(DEST_SEQUENCES_TABLE_NAME, DEST_SEQUENCES_TABLE_COLS);        
            }
        }
        stmt.close();
        
        for (String tableName : new String[] {OUTBOUND_MSGS_TABLE_NAME, INBOUND_MSGS_TABLE_NAME}) {
            stmt = connection.createStatement();
            try {
                stmt.executeUpdate(MessageFormat.format(CREATE_MESSAGES_TABLE_STMT, tableName));
            } catch (SQLException ex) {
                if (!isTableExistsError(ex)) {
                    throw ex;
                } else {
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine("Table " + tableName + " already exists.");
                    }
                    verifyTable(tableName, MESSAGES_TABLE_COLS);
                }
            }
            stmt.close();
        }
    }
    
    protected void verifyTable(String tableName, String[][] tableCols) {
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            ResultSet rs = metadata.getColumns(null, null, tableName, "%");
            Set<String> dbCols = new HashSet<String>();
            List<String[]> newCols = new ArrayList<String[]>(); 
            while (rs.next()) {
                dbCols.add(rs.getString(4));
            }
            for (String[] col : tableCols) {
                if (!dbCols.contains(col[0])) {
                    newCols.add(col);
                }
            }
            if (newCols.size() > 0) {
                // need to add the new columns
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "Table " + tableName + " needs additional columns");
                }
                
                for (String[] newCol : newCols) {
                    Statement st = null;
                    try {
                        st = connection.createStatement();
                        st.executeUpdate(MessageFormat.format(ALTER_TABLE_STMT_STR, 
                                                              tableName, newCol[0], newCol[1]));
                        if (LOG.isLoggable(Level.FINE)) {
                            LOG.log(Level.FINE, "Successfully added column {0} to table {1}",
                                    new Object[] {tableName, newCol[0]});
                        }
                    } finally {
                        if (st != null) {
                            st.close();
                        }
                    }
                }
            }
            
        } catch (SQLException ex) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("Table " + tableName + " cannot be verified.");
            }
        }
    }

    @PostConstruct     
    public synchronized void init() {
        
        if (null == connection) {
            LOG.log(Level.FINE, "Using derby.system.home: {0}", 
                    SystemPropertyAction.getProperty("derby.system.home"));
            assert null != url;
            assert null != driverClassName;
            try {
                Class.forName(driverClassName);
            } catch (ClassNotFoundException ex) {
                LogUtils.log(LOG, Level.SEVERE, "CONNECT_EXC", ex);
                return;
            }
    
            try {
                LOG.log(Level.FINE, "Using url: " + url);
                connection = DriverManager.getConnection(url, userName, password);
    
            } catch (SQLException ex) {
                LogUtils.log(LOG, Level.SEVERE, "CONNECT_EXC", ex);
                return;
            }
        }
        
        try {
            connection.setAutoCommit(true);
            createTables();
        } catch (SQLException ex) {
            LogUtils.log(LOG, Level.SEVERE, "CONNECT_EXC", ex);
            SQLException se = ex;
            while (se.getNextException() != null) {
                se = se.getNextException();
                LogUtils.log(LOG, Level.SEVERE, "CONNECT_EXC", se);
            }
            throw new RMStoreException(ex);
        } finally {
            try {
                connection.setAutoCommit(false);                
            } catch (SQLException ex) {
                LogUtils.log(LOG, Level.SEVERE, "CONNECT_EXC", ex);
                throw new RMStoreException(ex);
            }
        }
    }   
    
    Connection getConnection() {
        return connection;
    }
    
    public static void deleteDatabaseFiles() {
        deleteDatabaseFiles(DEFAULT_DATABASE_NAME, true);
    }
    
    public static void deleteDatabaseFiles(String dbName, boolean now) {
        String dsh = SystemPropertyAction.getPropertyOrNull("derby.system.home");
       
        File root = null;  
        File log = null;
        if (null == dsh) {
            log = new File("derby.log");
            root = new File(dbName);            
        } else {
            log = new File(dsh, "derby.log"); 
            root = new File(dsh, dbName);
        }
        if (log.exists()) {            
            if (now) {
                boolean deleted = log.delete();
                LOG.log(Level.FINE, "Deleted log file {0}: {1}", new Object[] {log, deleted});
            } else {
                log.deleteOnExit();
            }
        }
        if (root.exists()) {
            LOG.log(Level.FINE, "Trying to delete directory {0}", root);
            recursiveDelete(root, now);
        }
    
    }
    
    protected static String encodeProtocolVersion(ProtocolVariation pv) {
        return pv.getCodec().getWSRMNamespace() + ' ' + pv.getCodec().getWSANamespace(); 
    }

    protected static ProtocolVariation decodeProtocolVersion(String pv) {
        if (null != pv) {
            int d = pv.indexOf(' ');
            if (d > 0) {
                return ProtocolVariation.findVariant(pv.substring(0, d), pv.substring(d + 1));
            }
        }
        return ProtocolVariation.RM10WSA200408;
    }
    
    
    private static void recursiveDelete(File dir, boolean now) {
        for (File f : dir.listFiles()) {
            if (f.isDirectory()) {
                recursiveDelete(f, now);
            } else {
                if (now) {
                    f.delete();
                } else {
                    f.deleteOnExit();
                }
            }
        }
        if (now) {
            dir.delete();
        } else {
            dir.deleteOnExit();
        }
    }
     
    private static String buildCreateTableStatement(String name, String[][] cols, String[] keys) {
        StringBuffer buf = new StringBuffer();
        buf.append("CREATE TABLE ").append(name).append(" (");
        for (String[] col : cols) {
            buf.append(col[0]).append(" ").append(col[1]).append(", ");
        }
        buf.append("PRIMARY KEY (");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                buf.append(", ");
            }
            buf.append(keys[i]);
        }
        buf.append("))");
        return buf.toString();
    }

    protected boolean isTableExistsError(SQLException ex) {
        // we could be deriving the state/code from the driver url to avoid explicit setting of them
        return (null != tableExistsState && tableExistsState.equals(ex.getSQLState()))
                || tableExistsCode == ex.getErrorCode();
    }
}