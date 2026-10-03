/*
 * WebView 兼容补丁。
 *
 * DSH 前端按较新的 Chromium 编写，而本机 WebView（Chromium 114）缺其中一部分
 * 标准 API，缺失时会直接抛 TypeError 导致界面起不来。这里按标准语义补齐，
 * 且全部是幂等的：浏览器已实现就原样保留，不做覆盖。
 */
(function () {
  'use strict';

  // Chromium 119：Promise.withResolvers
  if (typeof Promise.withResolvers !== 'function') {
    Promise.withResolvers = function () {
      var resolve, reject;
      var promise = new Promise(function (res, rej) {
        resolve = res;
        reject = rej;
      });
      return { promise: promise, resolve: resolve, reject: reject };
    };
  }

  // Chromium 116：AbortSignal.any
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.any !== 'function') {
    AbortSignal.any = function (signals) {
      var controller = new AbortController();
      var signal = controller.signal;
      var list = Array.prototype.slice.call(signals);

      for (var i = 0; i < list.length; i++) {
        if (list[i].aborted) {
          controller.abort(list[i].reason);
          return signal;
        }
      }

      var onAbort = function (event) {
        for (var j = 0; j < list.length; j++) {
          list[j].removeEventListener('abort', onAbort);
        }
        controller.abort(event.target.reason);
      };

      for (var k = 0; k < list.length; k++) {
        list[k].addEventListener('abort', onAbort);
      }
      return signal;
    };
  }

  // Chromium 117：Object.groupBy
  if (typeof Object.groupBy !== 'function') {
    Object.groupBy = function (items, callback) {
      if (items == null) throw new TypeError('items is null or undefined');
      if (typeof callback !== 'function') throw new TypeError('callback is not a function');

      var result = Object.create(null);
      var index = 0;
      for (var item of items) {
        var key = callback(item, index++);
        var name = typeof key === 'symbol' ? key : String(key);
        if (!Object.prototype.hasOwnProperty.call(result, name)) {
          Object.defineProperty(result, name, {
            value: [],
            writable: true,
            enumerable: true,
            configurable: true,
          });
        }
        result[name].push(item);
      }
      return result;
    };
  }

  // Chromium 117：Map.groupBy
  if (typeof Map.groupBy !== 'function') {
    Map.groupBy = function (items, callback) {
      if (items == null) throw new TypeError('items is null or undefined');
      if (typeof callback !== 'function') throw new TypeError('callback is not a function');

      var result = new Map();
      var index = 0;
      for (var item of items) {
        var key = callback(item, index++);
        var group = result.get(key);
        if (group === undefined) {
          group = [];
          result.set(key, group);
        }
        group.push(item);
      }
      return result;
    };
  }

  // Chromium 122：Set 集合运算
  var setOps = {
    union: function (other) {
      var out = new Set(this);
      for (var v of other) out.add(v);
      return out;
    },
    intersection: function (other) {
      var out = new Set();
      for (var v of other) if (this.has(v)) out.add(v);
      return out;
    },
    difference: function (other) {
      var out = new Set();
      for (var v of this) if (!other.has(v)) out.add(v);
      return out;
    },
    symmetricDifference: function (other) {
      var out = new Set();
      for (var v of this) if (!other.has(v)) out.add(v);
      for (var w of other) if (!this.has(w)) out.add(w);
      return out;
    },
    isSubsetOf: function (other) {
      for (var v of this) if (!other.has(v)) return false;
      return true;
    },
    isSupersetOf: function (other) {
      for (var v of other) if (!this.has(v)) return false;
      return true;
    },
    isDisjointFrom: function (other) {
      for (var v of this) if (other.has(v)) return false;
      return true;
    },
  };
  for (var op in setOps) {
    if (typeof Set.prototype[op] !== 'function') {
      Object.defineProperty(Set.prototype, op, {
        value: setOps[op],
        writable: true,
        enumerable: false,
        configurable: true,
      });
    }
  }

  // Chromium 128：Promise.try
  if (typeof Promise.try !== 'function') {
    Promise.try = function (fn) {
      var args = Array.prototype.slice.call(arguments, 1);
      return new Promise(function (resolve) {
        resolve(fn.apply(undefined, args));
      });
    };
  }

  // Chromium 136：RegExp.escape
  if (typeof RegExp.escape !== 'function') {
    RegExp.escape = function (input) {
      var str = String(input);
      var out = '';
      for (var i = 0; i < str.length; i++) {
        var ch = str.charAt(i);
        if (i === 0 && /[0-9A-Za-z]/.test(ch)) {
          // 首字符是字母数字时必须写成转义序列，否则会被当成反向引用
          out += '\\x' + ch.charCodeAt(0).toString(16).padStart(2, '0');
        } else if (/[\\^$.*+?()[\]{}|/\-]/.test(ch)) {
          out += '\\' + ch;
        } else {
          out += ch;
        }
      }
      return out;
    };
  }

  // Chromium 120：URL.canParse
  if (typeof URL.canParse !== 'function') {
    URL.canParse = function (url, base) {
      try {
        new URL(url, base);
        return true;
      } catch (e) {
        return false;
      }
    };
  }

  // Chromium 130：Uint8Array.fromBase64 / toBase64
  if (typeof Uint8Array.fromBase64 !== 'function') {
    Uint8Array.fromBase64 = function (base64) {
      var binary = atob(base64);
      var bytes = new Uint8Array(binary.length);
      for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
      return bytes;
    };
  }
  if (typeof Uint8Array.prototype.toBase64 !== 'function') {
    Object.defineProperty(Uint8Array.prototype, 'toBase64', {
      value: function () {
        var binary = '';
        for (var i = 0; i < this.length; i++) binary += String.fromCharCode(this[i]);
        return btoa(binary);
      },
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }

  // Chromium 133：Map.prototype.getOrInsert / getOrInsertComputed
  if (typeof Map.prototype.getOrInsert !== 'function') {
    Object.defineProperty(Map.prototype, 'getOrInsert', {
      value: function (key, defaultValue) {
        if (this.has(key)) return this.get(key);
        this.set(key, defaultValue);
        return defaultValue;
      },
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }
  if (typeof Map.prototype.getOrInsertComputed !== 'function') {
    Object.defineProperty(Map.prototype, 'getOrInsertComputed', {
      value: function (key, callback) {
        if (this.has(key)) return this.get(key);
        var value = callback(key);
        this.set(key, value);
        return value;
      },
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }

  // Chromium 130：Array.fromAsync
  if (typeof Array.fromAsync !== 'function') {
    Array.fromAsync = function (items, mapFn, thisArg) {
      return (async function () {
        var result = [];
        var index = 0;
        for await (var item of items) {
          result.push(mapFn ? await mapFn.call(thisArg, item, index++) : item);
        }
        return result;
      })();
    };
  }

  // Chromium 134：显式资源管理符号
  if (typeof Symbol.dispose === 'undefined') {
    try {
      Symbol.dispose = Symbol.for('Symbol.dispose');
    } catch (e) {
      /* 忽略：个别实现把 Symbol 设为不可扩展 */
    }
  }
  if (typeof Symbol.asyncDispose === 'undefined') {
    try {
      Symbol.asyncDispose = Symbol.for('Symbol.asyncDispose');
    } catch (e) {
      /* 同上 */
    }
  }
})();
