package com.lul.shop.auth.infrastructure.persistence.repository;

import com.lul.shop.auth.domain.UserRole;
import com.lul.shop.auth.infrastructure.persistence.entity.RoleJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleJpaRepository extends JpaRepository<RoleJpaEntity, UUID> {

    @Query("""
        SELECT r 
        FROM RoleJpaEntity r
        WHERE r.name = :name
        """)
    Optional<RoleJpaEntity> findByName(@Param("name") UserRole name);

    @Query("""
            select r
            from RoleJpaEntity r
            where r.name in :names
            """)
    List<RoleJpaEntity> findAllByNames(@Param("names") Collection<UserRole> names);

}
