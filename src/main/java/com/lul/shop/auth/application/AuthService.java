package com.lul.shop.auth.application;


import com.lul.shop.auth.application.dto.AuthResult;
import com.lul.shop.auth.application.dto.LoginCommand;
import com.lul.shop.auth.application.dto.RegisterCommand;
import com.lul.shop.auth.application.port.PasswordHasher;
import com.lul.shop.auth.application.port.AccessTokenIssuer;
import com.lul.shop.auth.domain.User;
import com.lul.shop.auth.domain.UserRepository;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final AccessTokenIssuer accessTokenIssuer;

    public AuthService(UserRepository userRepository,
                       PasswordHasher passwordHasher,
                       AccessTokenIssuer accessTokenIssuer) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.accessTokenIssuer = accessTokenIssuer;
    }

    @Transactional
    public AuthResult register(RegisterCommand command) {
        if(userRepository.existsByEmail(command.email())) {
            throw new BusinessException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
        }

        String passwordHash = passwordHasher.hash(command.password());

        User user = User.create(
                command.email(),
                command.name(),
                passwordHash
        );

        User savedUser = userRepository.save(user);

        String token = accessTokenIssuer.createAccessToken(savedUser);

        return toResult(savedUser,token);
    }


    public AuthResult login(LoginCommand command) {
        User user = userRepository.findByEmail(command.email())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_CREDENTIALS));

        if(!passwordHasher.matches(command.password(), user.getPasswordHash())) {
            throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
        }

        if (!user.isEnabled()) {
            throw new BusinessException(AuthErrorCode.USER_DISABLED);
        }

        String accessToken = accessTokenIssuer.createAccessToken(user);

        return toResult(user, accessToken);
    }


    private AuthResult toResult(User user, String accessToken) {
        return new AuthResult(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRoles(),
                accessToken
        );
    }
}
