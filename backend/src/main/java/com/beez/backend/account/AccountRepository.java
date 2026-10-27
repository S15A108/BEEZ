package com.beez.backend.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByEmailAndDeletedAtIsNull(String email);

    Optional<Account> findByIdAndDeletedAtIsNull(Long id);
}
