package com.example.andvibe;

import androidx.annotation.Nullable;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

/** Rhino's call() signature, kept in Java so the override matches Object[]. */
abstract class JsFn extends BaseFunction {
    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        return invoke(args);
    }

    @Nullable
    protected abstract Object invoke(@Nullable Object[] args);
}
