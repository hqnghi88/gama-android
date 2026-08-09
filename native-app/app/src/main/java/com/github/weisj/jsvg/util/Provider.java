package com.github.weisj.jsvg.util;

@FunctionalInterface
public interface Provider<T> {
    T get();
}
