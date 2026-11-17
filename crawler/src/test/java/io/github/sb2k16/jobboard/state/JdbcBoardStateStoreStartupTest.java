package io.github.sb2k16.jobboard.state;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/** A database that refuses connections must not stop the pipeline from starting. */
class JdbcBoardStateStoreStartupTest {

    @Test
    void storeOpensWhilePostgresRefusesConnections() {
        // Port 1 on loopback: nothing listens, every connection is refused.
        final JdbcBoardStateStore store = assertDoesNotThrow(() -> new JdbcBoardStateStore(
                "jdbc:postgresql://127.0.0.1:1/jbi?connectTimeout=1", "jbi", "x", 2, 3));
        store.close();
    }
}
