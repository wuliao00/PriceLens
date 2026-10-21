/**
 * 极简 MD5（浏览器扩展 service worker / Node 共用）
 * -------------------------------------------------
 * B 站 WBI 签名需要 MD5；Web Crypto 不提供 MD5，因此内置一份纯 JS 实现。
 * 参考 RFC 1321 的标准四轮运算；只处理字符串 → 32 位小写 hex。
 */
(function (root) {
  'use strict';

  const K = new Uint32Array(64);
  for (let i = 0; i < 64; i += 1) {
    K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 0x100000000) >>> 0;
  }
  const S = [
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
  ];

  const add = (a, b) => (a + b) >>> 0;
  const rotl = (x, n) => ((x << n) | (x >>> (32 - n))) >>> 0;

  function md5(input) {
    const bytes = new TextEncoder().encode(String(input));
    const len = bytes.length;
    const words = ((len + 8) >> 6) * 16 + 16;
    const M = new Uint32Array(words);
    for (let i = 0; i < len; i += 1) {
      M[i >> 2] |= bytes[i] << ((i % 4) * 8);
    }
    M[len >> 2] |= 0x80 << ((len % 4) * 8);
    const bitLen = len * 8;
    M[words - 2] = bitLen >>> 0;
    M[words - 1] = Math.floor(bitLen / 0x100000000) >>> 0;

    let a0 = 0x67452301;
    let b0 = 0xefcdab89;
    let c0 = 0x98badcfe;
    let d0 = 0x10325476;

    for (let i = 0; i < words; i += 16) {
      let A = a0;
      let B = b0;
      let C = c0;
      let D = d0;
      for (let j = 0; j < 64; j += 1) {
        let F;
        let g;
        if (j < 16) {
          F = (B & C) | (~B & D);
          g = j;
        } else if (j < 32) {
          F = (D & B) | (~D & C);
          g = (5 * j + 1) % 16;
        } else if (j < 48) {
          F = B ^ C ^ D;
          g = (3 * j + 5) % 16;
        } else {
          F = C ^ (B | ~D);
          g = (7 * j) % 16;
        }
        const tmp = D;
        D = C;
        C = B;
        B = add(B, rotl(add(add(A, F >>> 0), add(K[j], M[i + g])), S[j]));
        A = tmp;
      }
      a0 = add(a0, A);
      b0 = add(b0, B);
      c0 = add(c0, C);
      d0 = add(d0, D);
    }

    const hex = (n) => {
      let out = '';
      for (let i = 0; i < 4; i += 1) {
        out += ((n >>> (i * 8)) & 0xff).toString(16).padStart(2, '0');
      }
      return out;
    };
    return hex(a0) + hex(b0) + hex(c0) + hex(d0);
  }

  root.md5 = md5;
  if (typeof module !== 'undefined' && module.exports) module.exports = md5;
})(typeof self !== 'undefined' ? self : this);
