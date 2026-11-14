package com.azentio.wiregate.api;

import java.util.List;

public record WirePageResponse(List<WireResponse> items, String nextCursor) {
}
