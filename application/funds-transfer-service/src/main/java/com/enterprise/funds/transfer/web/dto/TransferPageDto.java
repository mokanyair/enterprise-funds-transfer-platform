package com.enterprise.funds.transfer.web.dto;

import java.util.List;

public record TransferPageDto(List<TransferDto> items, int page, int size, long totalElements, int totalPages) {}
