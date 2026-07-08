package com.lul.shop.ordering.infrastructure.catalog;

import com.lul.shop.catalog.application.CatalogErrorCode;
import com.lul.shop.catalog.application.CatalogService;
import com.lul.shop.catalog.application.dto.ProductImageContent;
import com.lul.shop.ordering.application.OrderingErrorCode;
import com.lul.shop.ordering.application.dto.OrderItemImageContent;
import com.lul.shop.ordering.application.port.OrderItemImageClient;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class CatalogOrderItemImageAdapter implements OrderItemImageClient {

    private final CatalogService catalogService;

    public CatalogOrderItemImageAdapter(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public OrderItemImageContent loadOrderItemImage(String imageKey) {
        try {
            ProductImageContent image = catalogService.getProductImageByKey(imageKey);

            return new OrderItemImageContent(
                    image.content(),
                    image.contentType(),
                    image.contentLength()
            );
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == CatalogErrorCode.PRODUCT_IMAGE_NOT_FOUND) {
                throw new BusinessException(OrderingErrorCode.ORDER_ITEM_IMAGE_NOT_FOUND);
            }

            if (ex.getErrorCode() == CatalogErrorCode.PRODUCT_IMAGE_READ_FAILED) {
                throw new BusinessException(OrderingErrorCode.ORDER_ITEM_IMAGE_READ_FAILED);
            }

            throw ex;
        }
    }
}