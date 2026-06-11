package com.lul.shop.auth.application.port;

import com.lul.shop.auth.domain.User;

public interface TokenProvider {

    String createAccessToken(User user);
}