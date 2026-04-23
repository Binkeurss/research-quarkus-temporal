package org.acme.catalog.resources;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.acme.catalog.dtos.CursorPageResponse;
import org.acme.catalog.dtos.ProductCreateRequest;
import org.acme.catalog.dtos.ProductResponse;
import org.acme.catalog.dtos.ProductUpdateRequest;
import org.acme.catalog.services.ProductServices;

@Path("/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductResources {

    @Inject
    ProductServices productServices;

    @POST
    public ProductResponse create(@Valid ProductCreateRequest request) {
        return productServices.create(request);
    }

    @GET
    public CursorPageResponse<ProductResponse> getAll(
            @QueryParam("cursor") Long cursor,
            @QueryParam("limit") @DefaultValue("10") int limit
    ) {
        return productServices.getAll(cursor, limit);
    }

    @GET
    @Path("/{id}")
    public ProductResponse getById(@PathParam("id") Long id) {
        return productServices.getById(id);
    }

    @PUT
    @Path("/{id}")
    public ProductResponse update(@PathParam("id") Long id, @Valid ProductUpdateRequest request) {
        return productServices.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") Long id) {
        productServices.delete(id);
    }
}