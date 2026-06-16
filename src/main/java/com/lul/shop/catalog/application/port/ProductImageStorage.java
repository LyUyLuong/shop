package com.lul.shop.catalog.application.port;

import com.lul.shop.catalog.application.dto.StoredProductImage;
import com.lul.shop.catalog.application.dto.UploadProductImageCommand;

import java.util.UUID;

public interface ProductImageStorage {

    StoredProductImage store(UUID productId, UploadProductImageCommand command);
}