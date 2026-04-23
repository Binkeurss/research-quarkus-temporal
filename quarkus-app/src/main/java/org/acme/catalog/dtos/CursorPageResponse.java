package org.acme.catalog.dtos;

import java.util.List;

public class CursorPageResponse<T> {

    public List<T> data;
    public Long nextCursor;
    public Boolean hasNext;
}