package com.lul.shop.auth.infrastructure.persistence.repository;

import com.lul.shop.auth.infrastructure.persistence.entity.UserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    @Query("""
            select distinct u
            from UserJpaEntity u
            left join fetch u.roles
            where u.id = :id
            """)
    Optional<UserJpaEntity> findByIdWithRoles(@Param("id") UUID id);

    @Query("""
            select distinct u
            from UserJpaEntity u
            left join fetch u.roles
            where lower(u.email) = lower(:email)
            """)
    Optional<UserJpaEntity> findByEmailWithRoles(@Param("email") String email);

    @Query("""
            select count(u) > 0
            from UserJpaEntity u
            where lower(u.email) = lower(:email)
            """)
    boolean existsByEmail(@Param("email") String email);
}