package com.lul.shop.auth.application;

import com.lul.shop.auth.application.dto.AuthResult;
import com.lul.shop.auth.application.dto.LoginCommand;
import com.lul.shop.auth.application.dto.RegisterCommand;
import com.lul.shop.auth.application.port.AccessTokenIssuer;
import com.lul.shop.auth.application.port.PasswordHasher;
import com.lul.shop.auth.domain.User;
import com.lul.shop.auth.domain.UserRepository;
import com.lul.shop.auth.domain.UserRole;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final String RAW_PASSWORD = "plain-password";
    private static final String PASSWORD_HASH = "encoded-password";
    private static final String ACCESS_TOKEN = "access-token";

    @Test
    void shouldRegisterEnabledUserWithNormalizedEmailAndUserRole() {
        FakeUserRepository repository = new FakeUserRepository();
        FakePasswordHasher passwordHasher = new FakePasswordHasher();
        FakeAccessTokenIssuer tokenIssuer = new FakeAccessTokenIssuer();

        passwordHasher.hashResult = PASSWORD_HASH;
        tokenIssuer.token = ACCESS_TOKEN;

        AuthService service =
                new AuthService(repository, passwordHasher, tokenIssuer);

        AuthResult result = service.register(new RegisterCommand(
                " Customer@Example.COM ",
                " Customer ",
                RAW_PASSWORD
        ));

        assertThat(repository.savedUsers).hasSize(1);

        User savedUser = repository.savedUsers.get(0);

        assertThat(savedUser.getEmail()).isEqualTo("customer@example.com");
        assertThat(savedUser.getName()).isEqualTo("Customer");
        assertThat(savedUser.getPasswordHash()).isEqualTo(PASSWORD_HASH);
        assertThat(savedUser.isEnabled()).isTrue();
        assertThat(savedUser.getRoles()).containsExactly(UserRole.USER);

        assertThat(result.userId()).isEqualTo(savedUser.getId());
        assertThat(result.email()).isEqualTo("customer@example.com");
        assertThat(result.name()).isEqualTo("Customer");
        assertThat(result.roles()).containsExactly(UserRole.USER);
        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);

        assertThat(passwordHasher.hashCalls)
                .containsExactly(RAW_PASSWORD);
        assertThat(tokenIssuer.issuedUsers)
                .containsExactly(savedUser);
    }

    @Test
    void shouldRejectRegistrationWhenEmailAlreadyExists() {
        FakeUserRepository repository = new FakeUserRepository();
        repository.emailExists = true;

        FakePasswordHasher passwordHasher = new FakePasswordHasher();
        FakeAccessTokenIssuer tokenIssuer = new FakeAccessTokenIssuer();

        AuthService service =
                new AuthService(repository, passwordHasher, tokenIssuer);

        assertThatThrownBy(() -> service.register(new RegisterCommand(
                "customer@example.com",
                "Customer",
                RAW_PASSWORD
        )))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.EMAIL_ALREADY_EXISTS)
                );

        assertThat(passwordHasher.hashCalls).isEmpty();
        assertThat(repository.savedUsers).isEmpty();
        assertThat(tokenIssuer.issuedUsers).isEmpty();
    }

    @Test
    void shouldLoginAndIssueTokenWhenCredentialsAreValid() {
        User user = existingUser(true);

        FakeUserRepository repository = new FakeUserRepository();
        repository.foundUser = user;

        FakePasswordHasher passwordHasher = new FakePasswordHasher();
        passwordHasher.matchesResult = true;

        FakeAccessTokenIssuer tokenIssuer = new FakeAccessTokenIssuer();
        tokenIssuer.token = ACCESS_TOKEN;

        AuthService service =
                new AuthService(repository, passwordHasher, tokenIssuer);

        AuthResult result = service.login(new LoginCommand(
                "customer@example.com",
                RAW_PASSWORD
        ));

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.email()).isEqualTo("customer@example.com");
        assertThat(result.name()).isEqualTo("Customer");
        assertThat(result.roles()).containsExactly(UserRole.USER);
        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);

        assertThat(passwordHasher.matchCalls)
                .containsExactly(new PasswordMatchCall(
                        RAW_PASSWORD,
                        PASSWORD_HASH
                ));

        assertThat(tokenIssuer.issuedUsers).containsExactly(user);
    }

    @Test
    void shouldRejectLoginWhenUserDoesNotExist() {
        FakeUserRepository repository = new FakeUserRepository();
        FakePasswordHasher passwordHasher = new FakePasswordHasher();
        FakeAccessTokenIssuer tokenIssuer = new FakeAccessTokenIssuer();

        AuthService service =
                new AuthService(repository, passwordHasher, tokenIssuer);

        assertThatThrownBy(() -> service.login(new LoginCommand(
                "missing@example.com",
                RAW_PASSWORD
        )))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS)
                );

        assertThat(passwordHasher.matchCalls).isEmpty();
        assertThat(tokenIssuer.issuedUsers).isEmpty();
    }

    @Test
    void shouldReturnInvalidCredentialsBeforeRevealingDisabledAccountWhenPasswordIsWrong() {
        FakeUserRepository repository = new FakeUserRepository();
        repository.foundUser = existingUser(false);

        FakePasswordHasher passwordHasher = new FakePasswordHasher();
        passwordHasher.matchesResult = false;

        FakeAccessTokenIssuer tokenIssuer = new FakeAccessTokenIssuer();

        AuthService service =
                new AuthService(repository, passwordHasher, tokenIssuer);

        assertThatThrownBy(() -> service.login(new LoginCommand(
                "customer@example.com",
                "wrong-password"
        )))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS)
                );

        assertThat(tokenIssuer.issuedUsers).isEmpty();
    }

    @Test
    void shouldRejectLoginWhenUserIsDisabled() {
        FakeUserRepository repository = new FakeUserRepository();
        repository.foundUser = existingUser(false);

        FakePasswordHasher passwordHasher = new FakePasswordHasher();
        passwordHasher.matchesResult = true;

        FakeAccessTokenIssuer tokenIssuer = new FakeAccessTokenIssuer();

        AuthService service =
                new AuthService(repository, passwordHasher, tokenIssuer);

        assertThatThrownBy(() -> service.login(new LoginCommand(
                "customer@example.com",
                RAW_PASSWORD
        )))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.USER_DISABLED)
                );

        assertThat(tokenIssuer.issuedUsers).isEmpty();
    }

    private static User existingUser(boolean enabled) {
        return new User(
                USER_ID,
                "customer@example.com",
                "Customer",
                PASSWORD_HASH,
                enabled,
                Set.of(UserRole.USER),
                null,
                null
        );
    }

    private static class FakeUserRepository implements UserRepository {

        private User foundUser;
        private boolean emailExists;
        private final List<User> savedUsers = new ArrayList<>();

        @Override
        public Optional<User> findById(UUID id) {
            return Optional.ofNullable(foundUser)
                    .filter(user -> user.getId().equals(id));
        }

        @Override
        public Optional<User> findByEmail(String email) {
            return Optional.ofNullable(foundUser);
        }

        @Override
        public boolean existsByEmail(String email) {
            return emailExists;
        }

        @Override
        public User save(User user) {
            foundUser = user;
            savedUsers.add(user);
            return user;
        }
    }

    private static class FakePasswordHasher implements PasswordHasher {

        private String hashResult = PASSWORD_HASH;
        private boolean matchesResult;
        private final List<String> hashCalls = new ArrayList<>();
        private final List<PasswordMatchCall> matchCalls =
                new ArrayList<>();

        @Override
        public String hash(String rawPassword) {
            hashCalls.add(rawPassword);
            return hashResult;
        }

        @Override
        public boolean matches(
                String rawPassword,
                String passwordHash
        ) {
            matchCalls.add(new PasswordMatchCall(
                    rawPassword,
                    passwordHash
            ));
            return matchesResult;
        }
    }

    private static class FakeAccessTokenIssuer
            implements AccessTokenIssuer {

        private String token = ACCESS_TOKEN;
        private final List<User> issuedUsers = new ArrayList<>();

        @Override
        public String createAccessToken(User user) {
            issuedUsers.add(user);
            return token;
        }
    }

    private record PasswordMatchCall(
            String rawPassword,
            String passwordHash
    ) {
    }
}