package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.*;
import com.beez.backend.support.IntegrationTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ImpactMigrationIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource source;

    @Test void upgradesV16WithHistoricalPublicationsWithoutSchedulingPastAiWork() throws Exception {
        var pool=source.unwrap(HikariDataSource.class);
        String name="impact_migration_"+UUID.randomUUID().toString().replace("-","");
        var admin=new JdbcTemplate(source);
        admin.execute("CREATE DATABASE "+name);
        try {
            var isolated=new PGSimpleDataSource();
            String url=pool.getJdbcUrl();
            isolated.setURL(url.substring(0,url.lastIndexOf('/')+1)+name);
            isolated.setUser(pool.getUsername()); isolated.setPassword(pool.getPassword());
            Flyway.configure().dataSource(isolated).target("16").load().migrate();
            var jdbc=new JdbcTemplate(isolated);
            long account=jdbc.queryForObject("INSERT INTO account(email,password_hash,display_name) VALUES('old@test','x','old') RETURNING id",Long.class);
            long project=jdbc.queryForObject("INSERT INTO project(name,owner_account_id) VALUES('old',?) RETURNING id",Long.class,account);
            long first=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id",Long.class,project,account);
            long proposal=jdbc.queryForObject("""
                    INSERT INTO change_proposal(project_id,key,status,base_baseline_id,proposer_kind,proposer_account_id,reason)
                    VALUES(?,'CP-OLD','APPROVED',?,'HUMAN',?,'old') RETURNING id
                    """,Long.class,project,first,account);
            jdbc.update("INSERT INTO baseline(project_id,version_no,published_by,change_proposal_id) VALUES(?,2,?,?)",project,account,proposal);
            Flyway.configure().dataSource(isolated).load().migrate();
            assertThat(jdbc.queryForList("SELECT status FROM published_change_analysis",String.class))
                    .containsExactly("NOT_EVALUATED","NOT_EVALUATED");
            assertThat(jdbc.queryForList("SELECT error_code FROM published_change_analysis",String.class))
                    .containsOnly("published_before_intake");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM change_impact_run",Integer.class)).isZero();
        } finally {
            admin.execute("DROP DATABASE "+name+" WITH (FORCE)");
        }
    }
}
