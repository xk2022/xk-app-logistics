package com.xksystem.logistics.support;

import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.evidence.EvidenceStorage;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Same-transaction private bytea storage, exclusively for isolated synthetic acceptance. */
public final class SyntheticEvidenceStorage implements EvidenceStorage {
    private final DataSource source;private final JdbcTemplate jdbc;
    private SyntheticEvidenceStorage(DataSource source){this.source=source;this.jdbc=new JdbcTemplate(source);}
    public static EvidenceStorage create(DataSource source)throws SQLException {
        SyntheticEvidencePolicy.requireIsolatedDatabase(source);return new SyntheticEvidenceStorage(source);
    }
    public PolicyMode mode(){return PolicyMode.SYNTHETIC_TEST_ONLY;}
    private void transaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!TransactionSynchronizationManager.hasResource(source))throw new IllegalTransactionStateException("Synthetic private storage requires the caller's bound writable transaction");
    }
    public void store(UUID objectId,byte[] bytes,String sha256) {
        transaction();jdbc.update("INSERT INTO logistics.tms_execution_evidence_object(id,content,byte_size,sha256) VALUES (?,?,?,?)",objectId,bytes,bytes.length,sha256);
    }
    public byte[] load(UUID objectId) {
        transaction();return jdbc.queryForObject("SELECT content FROM logistics.tms_execution_evidence_object WHERE id=?",byte[].class,objectId);
    }
}
