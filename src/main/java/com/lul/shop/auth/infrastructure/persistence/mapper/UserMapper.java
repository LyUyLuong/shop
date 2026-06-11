package com.lul.shop.auth.infrastructure.persistence.mapper;

import com.lul.shop.auth.domain.User;
import com.lul.shop.auth.domain.UserRole;
import com.lul.shop.auth.infrastructure.persistence.entity.RoleJpaEntity;
import com.lul.shop.auth.infrastructure.persistence.entity.UserJpaEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "roles", expression = "java(toDomainRoles(entity.getRoles()))")
    User toDomain(UserJpaEntity entity);

    @Mapping(target = "roles", source = "roles")
    UserJpaEntity toEntity(User user, Set<RoleJpaEntity> roles);

    default Set<UserRole> toDomainRoles(Set<RoleJpaEntity> roles) {
        if (roles == null || roles.isEmpty()) {
            return EnumSet.of(UserRole.USER);
        }

        return roles.stream()
                .map(RoleJpaEntity::getName)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(UserRole.class)));
    }
}