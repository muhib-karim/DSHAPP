/*
 * WebView 兼容补丁。
 *
 * DSH 前端按较新的 Chromium 编写，而本机 WebView（Chromium 114）缺其中一部分
 * 标准 API，缺失时会直接抛 TypeError 导致界面起不来。这里按标准语义补齐，
 * 且全部是幂等的：浏览器已实现就原样保留，不做覆盖。
 */
(function () {
  'use strict';

  // ── 启动期错误上报 ─────────────────────────────────────────────────
  //
  // DSH 官方前端的入口 bundle 用了较新的**语法**（如 class static block，
  // Chromium 94+）。语法补不了 —— 下面那些补丁只能补**运行时 API**（缺函数时挂一个）。
  // 一旦命中语法门槛，整份 chunk 在**解析阶段**就失败、一行都不执行：
  // HTML 与 CSS 都正常（页面是官方前端自己的白底启动页），`#root` 却永远是空的，
  // 宿主侧什么都看不到，用户只看到白屏。
  //
  // 真机现象（用户反馈的 45 秒录屏，vivo Y52s / Android 10）：
  //   `onPageFinished` 触发了、启动层走到了「正在准备界面…」、页面底色也是官方白，
  //   但状态栏全程停在主题里的深色 —— 说明连插件的 `onStatus` 都没上报过，
  //   即页面里的 JS 一行都没跑起来。12 秒后启动层被兜底收起，露出白屏。
  //
  // 所以这里把错误抓出来交给原生显示（`DSHAndroid.reportWebError`），
  // 让「白屏」变成「白屏 + 原因」—— 否则这类失败在设备上完全无声。
  //
  // ⚠️ 必须 `capture: true`：资源加载失败（script/link 404）**不冒泡**，
  //   只有捕获阶段能拿到，而它的表现与语法错误一模一样（都是页面起不来）。
  // ⚠️ 只报第一条：启动失败时后续错误多半是它的连锁反应，第一条才是原因。
  (function () {
    var reported = false;

    function report(text) {
      if (reported) return;
      reported = true;
      try {
        var bridge = window.DSHAndroid;
        if (bridge && typeof bridge.reportWebError === 'function') {
          bridge.reportWebError(String(text).slice(0, 300));
        }
      } catch (e) {
        /* 桥不可用（桌面端）时只留在控制台 */
      }
      try {
        console.error('[dsh-webview] ' + text);
      } catch (e) {
        /* 忽略 */
      }
    }

    window.addEventListener(
      'error',
      function (event) {
        var target = event.target;
        // 资源加载失败：这类 event 上没有 message，只有 target
        if (target && target !== window && target.tagName) {
          report('资源加载失败: ' + (target.src || target.href || target.tagName));
          return;
        }
        report(
          (event.message || (event.error && event.error.message) || '脚本错误') +
            (event.filename ? ' @' + event.filename + ':' + event.lineno : '')
        );
      },
      true
    );

    window.addEventListener('unhandledrejection', function (event) {
      var reason = event.reason;
      report('未处理的 Promise 拒绝: ' + ((reason && reason.message) || String(reason)));
    });
  })();

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

  // Chromium 95：reportError
  //
  // 前端 bundle 用它上报未捕获异常。缺这个函数时**调用点自己**会抛 TypeError ——
  // 一个本来可恢复的错误因此升级成致命错误，比不报还糟。
  // 标准语义是「按未捕获异常处理」，这里用异步抛出近似，WebView 照常走 window.onerror。
  if (typeof reportError !== 'function') {
    self.reportError = function (error) {
      setTimeout(function () {
        throw error;
      }, 0);
    };
  }

  // ── 内核 83 及更旧：语法降级之后仍缺的**运行时 API** ────────────────
  //
  // 上面那些是「新版内核也缺」的补丁。这一段是另一回事：官方 bundle 已被
  // `tools/build-webview-compat.ps1` 降到 chrome74 编译（语法层面能解析了），
  // 但 **esbuild 只管语法，不管 API** —— 降级产物里仍在调用这些函数。
  // 实测降级后的两个大 bundle 里还有：
  //   index  : .at() ×14、Object.hasOwn ×7、replaceAll ×5、reportError ×2、
  //            AggregateError ×1、WeakRef ×1
  //   vendor : .at() ×12
  //
  // ⚠️ 这几处的版本门槛都比 74 高，而它们**必须**和降级 bundle 一起生效 ——
  //    少任何一个，对应调用点就会抛 TypeError。参见 MainActivity 的
  //    `MIN_WEBVIEW_MAJOR_COMPAT`。

  // Chromium 92：Array/String.prototype.at
  // 负索引是它的核心语义（`arr.at(-1)`），不能简化成 `arr[i]`。
  (function () {
    function at(index) {
      var len = this.length;
      var i = Math.trunc(Number(index)) || 0;
      if (i < 0) i += len;
      if (i < 0 || i >= len) return undefined;
      return this[i];
    }
    var targets = [Array.prototype, String.prototype];
    // TypedArray 的原型不直接挂在 %TypedArray%.prototype 上，逐个取。
    if (typeof Uint8Array !== 'undefined') {
      targets.push(
        Object.getPrototypeOf(Uint8Array.prototype),
        Uint8Array.prototype,
        Int8Array.prototype,
        Uint16Array.prototype,
        Int16Array.prototype,
        Uint32Array.prototype,
        Int32Array.prototype,
        Float32Array.prototype,
        Float64Array.prototype
      );
    }
    for (var i = 0; i < targets.length; i++) {
      var proto = targets[i];
      if (proto && typeof proto.at !== 'function') {
        Object.defineProperty(proto, 'at', {
          value: at,
          writable: true,
          enumerable: false,
          configurable: true,
        });
      }
    }
  })();

  // Chromium 93：Object.hasOwn
  // 与 `hasOwnProperty` 的差别是**不查原型链、对 null 原型对象也安全**。
  if (typeof Object.hasOwn !== 'function') {
    Object.hasOwn = function (target, key) {
      if (target == null) throw new TypeError('Cannot convert undefined or null to object');
      return Object.prototype.hasOwnProperty.call(Object(target), key);
    };
  }

  // Chromium 85：String.prototype.replaceAll
  // ⚠️ 第一参数是字符串时必须**全局**替换（这正是它与 `replace` 的唯一差别）；
  //   传正则时不加 g 标志，标准要求直接抛 TypeError。
  // ⚠️ 字符串分支不要用 `split/join` —— 那样替换串里的 `$&`、`$$`、`$1` 就不会
  //   按标准展开。转义成字面量正则后交给原生 `replace`，展开语义由引擎负责。
  if (typeof String.prototype.replaceAll !== 'function') {
    Object.defineProperty(String.prototype, 'replaceAll', {
      value: function (search, replacement) {
        if (search instanceof RegExp) {
          if (!search.global) {
            throw new TypeError('replaceAll must be called with a global RegExp');
          }
          return this.replace(search, replacement);
        }
        var str = String(this);
        var needle = String(search);
        if (needle === '') return str;
        var escaped = needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        return str.replace(new RegExp(escaped, 'g'), replacement);
      },
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }

  // Chromium 85：AggregateError
  // `Promise.any` 的拒绝值就是它，而 bundle 里既构造也 `instanceof` 判断。
  if (typeof AggregateError === 'undefined') {
    self.AggregateError = (function () {
      function AggregateError(errors, message) {
        var err = new Error(message);
        Object.setPrototypeOf(err, AggregateError.prototype);
        err.name = 'AggregateError';
        err.errors = Array.from(errors || []);
        return err;
      }
      AggregateError.prototype = Object.create(Error.prototype);
      AggregateError.prototype.constructor = AggregateError;
      return AggregateError;
    })();
  }

  // Chromium 84：WeakRef
  //
  // ⚠️ 这个降级**只能近似**：真正的弱引用语义（不阻止 GC、`.deref()` 可能返回
  //    undefined）在旧内核上无法用 JS 复刻。这里用强引用顶替，`.deref()` 恒返回原对象。
  //    代价是该对象不会被回收 —— 对本应用（一个本地 GUI 页）可以接受，
  //    总好过 `new WeakRef(...)` 直接抛 ReferenceError 让整个 chunk 挂掉。
  if (typeof WeakRef === 'undefined') {
    self.WeakRef = (function () {
      function WeakRef(target) {
        if (!(this instanceof WeakRef)) throw new TypeError("Constructor WeakRef requires 'new'");
        this._target = target;
      }
      WeakRef.prototype.deref = function () {
        return this._target;
      };
      return WeakRef;
    })();
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
