package com.lingxiao.search.service;

import java.util.List;

public record IndexResult(int requested, int indexed, List<String> missing) {
}

