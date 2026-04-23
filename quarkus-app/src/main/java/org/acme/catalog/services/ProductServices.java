package org.acme.catalog.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.acme.catalog.dtos.AttributeRequest;
import org.acme.catalog.dtos.CursorPageResponse;
import org.acme.catalog.dtos.ProductCreateRequest;
import org.acme.catalog.dtos.ProductResponse;
import org.acme.catalog.dtos.ProductUpdateRequest;
import org.acme.catalog.dtos.VariantRequest;
import org.acme.catalog.entities.Attribute;
import org.acme.catalog.entities.AttributeValue;
import org.acme.catalog.entities.Product;
import org.acme.catalog.entities.ProductVariant;
import org.acme.catalog.mappers.ProductMapper;
import org.acme.catalog.repositories.AttributeRepository;
import org.acme.catalog.repositories.AttributeValueRepository;
import org.acme.catalog.repositories.ProductRepository;
import org.acme.catalog.repositories.ProductVariantRepository;
import org.acme.catalog.repositories.VariantAttributeRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductServices {

    @Inject
    ProductRepository productRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    AttributeRepository attributeRepository;

    @Inject
    AttributeValueRepository attributeValueRepository;

    @Inject
    VariantAttributeRepository variantAttributeRepository;

    @Inject
    ProductMapper productMapper;

    @Transactional
    public ProductResponse create(ProductCreateRequest request) {
        validateCreate(request);

        Product product = productRepository.create(
                request.name,
                request.slug,
                request.description,
                request.coverImage,
                request.status
        );

        createVariants(product, request.variants);

        return productMapper.toResponse(product);
    }

    public ProductResponse getById(Long id) {
        Product product = productRepository.findByIdOptional(id)
                .orElseThrow(() -> new NotFoundException("Product not found"));

        return productMapper.toResponse(product);
    }

    public CursorPageResponse<ProductResponse> getAll(Long cursor, int limit) {
        validateCursorPagination(limit);

        List<Product> products = productRepository.findAfterCursor(cursor, limit + 1);

        boolean hasNext = products.size() > limit;
        if (hasNext) {
            products = products.subList(0, limit);
        }

        Long nextCursor = null;
        if (hasNext && !products.isEmpty()) {
            nextCursor = products.get(products.size() - 1).id;
        }

        CursorPageResponse<ProductResponse> response = new CursorPageResponse<>();
        response.data = products.stream()
                .map(productMapper::toResponse)
                .collect(Collectors.toList());
        response.nextCursor = nextCursor;
        response.hasNext = hasNext;

        return response;
    }

    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        Product product = productRepository.findByIdOptional(id)
                .orElseThrow(() -> new NotFoundException("Product not found"));

        validateUpdate(id, request);

        productRepository.update(
                product,
                request.name,
                request.slug,
                request.description,
                request.coverImage,
                request.status
        );

        productVariantRepository.deleteByProduct(product);
        createVariants(product, request.variants);

        return productMapper.toResponse(product);
    }

    @Transactional
    public void delete(Long id) {
        Product product = productRepository.findByIdOptional(id)
                .orElseThrow(() -> new NotFoundException("Product not found"));

        productRepository.delete(product);
    }

    private void createVariants(Product product, List<VariantRequest> variants) {
        if (variants == null || variants.isEmpty()) {
            return;
        }

        for (VariantRequest variantRequest : variants) {
            ProductVariant variant = productVariantRepository.create(
                    product,
                    variantRequest.skuCode,
                    variantRequest.variantName,
                    variantRequest.price,
                    variantRequest.stock
            );

            if (variantRequest.attributes == null || variantRequest.attributes.isEmpty()) {
                continue;
            }

            for (AttributeRequest attributeRequest : variantRequest.attributes) {
                Attribute attribute = attributeRepository.findOrCreate(attributeRequest.name);
                AttributeValue attributeValue =
                        attributeValueRepository.findOrCreate(attribute, attributeRequest.value);

                variantAttributeRepository.create(variant, attributeValue);
            }
        }
    }

    private void validateCreate(ProductCreateRequest request) {
        if (productRepository.existsBySlug(request.slug)) {
            throw new BadRequestException("Slug already exists");
        }

        validateVariantRequests(request.variants);
    }

    private void validateUpdate(Long productId, ProductUpdateRequest request) {
        if (productRepository.existsBySlugAndIdNot(request.slug, productId)) {
            throw new BadRequestException("Slug already exists");
        }

        validateVariantRequests(request.variants);
    }

    private void validateVariantRequests(List<VariantRequest> variants) {
        if (variants == null || variants.isEmpty()) {
            return;
        }

        Set<String> skuCodesInRequest = new HashSet<>();

        for (VariantRequest variant : variants) {
            if (!skuCodesInRequest.add(variant.skuCode)) {
                throw new BadRequestException("Duplicate skuCode in request: " + variant.skuCode);
            }

            if (productVariantRepository.existsBySkuCode(variant.skuCode)) {
                throw new BadRequestException("skuCode already exists: " + variant.skuCode);
            }
        }
    }

    private void validateCursorPagination(int limit) {
        if (limit <= 0 || limit > 100) {
            throw new BadRequestException("limit must be between 1 and 100");
        }
    }
}