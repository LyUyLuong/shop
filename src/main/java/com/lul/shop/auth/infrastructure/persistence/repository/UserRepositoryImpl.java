package com.lul.shop.auth.infrastructure.persistence.repository;

import com.lul.shop.auth.domain.User;
import com.lul.shop.auth.domain.UserRepository;
import com.lul.shop.auth.domain.UserRole;
import com.lul.shop.auth.infrastructure.persistence.entity.RoleJpaEntity;
import com.lul.shop.auth.infrastructure.persistence.entity.UserJpaEntity;
import com.lul.shop.auth.infrastructure.persistence.mapper.UserMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@Transactional(readOnly = true)
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpaRepository;
    private final RoleJpaRepository roleJpaRepository;
    private final UserMapper userMapper;

    public UserRepositoryImpl(UserJpaRepository userJpaRepository,
                              RoleJpaRepository roleJpaRepository,
                              UserMapper userMapper) {
        this.userJpaRepository = userJpaRepository;
        this.roleJpaRepository = roleJpaRepository;
        this.userMapper = userMapper;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return userJpaRepository.findByIdWithRoles(id)
                .map(userMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return normalizeEmail(email)
                .flatMap(userJpaRepository::findByEmailWithRoles)
                .map(userMapper::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return normalizeEmail(email)
                .map(userJpaRepository::existsByEmail)
                .orElse(false);
    }

    @Override
    @Transactional
    public User save(User user) {
        Set<RoleJpaEntity> roles = resolveRoles(user.getRoles());
        UserJpaEntity entity = userMapper.toEntity(user, roles);
        UserJpaEntity savedEntity = userJpaRepository.save(entity);

        return userMapper.toDomain(savedEntity);
    }

    private Set<RoleJpaEntity> resolveRoles(Set<UserRole> roleNames) {
        Set<UserRole> requestedRoleNames = normalizeRoleNames(roleNames);
        List<RoleJpaEntity> roleEntities = roleJpaRepository.findAllByNames(requestedRoleNames);

        Set<UserRole> foundRoleNames = roleEntities.stream()
                .map(RoleJpaEntity::getName)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(UserRole.class)));

        if (!foundRoleNames.containsAll(requestedRoleNames)) {
            Set<UserRole> missingRoleNames = EnumSet.copyOf(requestedRoleNames);
            missingRoleNames.removeAll(foundRoleNames);

            throw new IllegalStateException("Missing role seed data: " + missingRoleNames);
        }

        return new HashSet<>(roleEntities);
    }

    private Set<UserRole> normalizeRoleNames(Set<UserRole> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return EnumSet.of(UserRole.USER);
        }

        return EnumSet.copyOf(roleNames);
    }

    private Optional<String> normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(email.trim().toLowerCase(Locale.ROOT));
    }
}