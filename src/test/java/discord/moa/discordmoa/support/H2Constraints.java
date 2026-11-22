package discord.moa.discordmoa.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * 이 환경의 H2(2.4.240)는 Hibernate가 enum 컬럼에 붙이는 check(col in (...)) 제약을 올바른 값에도
 * "Check constraint invalid"로 거절한다. 운영 DB(MySQL)와 무관한 문제라 테스트 DB에서만 check 제약을 지운다.
 */
public final class H2Constraints {

    private H2Constraints() {
    }

    public static void dropChecks(EntityManagerFactory entityManagerFactory) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            entityManager.unwrap(Session.class).doWork(connection -> {
                List<String> drops = new ArrayList<>();
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("select TABLE_NAME, CONSTRAINT_NAME from INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                             + "where TABLE_SCHEMA = 'PUBLIC' and CONSTRAINT_TYPE = 'CHECK'")) {
                    while (rows.next()) {
                        drops.add("alter table " + rows.getString(1) + " drop constraint " + rows.getString(2));
                    }
                }
                for (String drop : drops) {
                    try (var statement = connection.createStatement()) {
                        statement.execute(drop);
                    }
                }
            });
        } finally {
            entityManager.close();
        }
    }
}
