const utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });

export function parseStrictJson(bytes, maxDepth = 64) {
  const source = typeof bytes === 'string' ? bytes : utf8.decode(bytes);
  let offset = 0;
  const whitespace = () => { while (/[\x20\x09\x0a\x0d]/.test(source[offset] ?? '') && offset < source.length) offset++; };
  const fail = () => { throw new SyntaxError('Invalid or ambiguous JSON'); };
  function string() {
    if (source[offset++] !== '"') fail();
    const start = offset - 1;
    while (offset < source.length) {
      const character = source[offset++];
      if (character === '"') {
        const decoded = JSON.parse(source.slice(start, offset));
        for (let index = 0; index < decoded.length; index++) {
          const unit = decoded.charCodeAt(index);
          if (unit >= 0xd800 && unit <= 0xdbff) {
            const next = decoded.charCodeAt(++index);
            if (!(next >= 0xdc00 && next <= 0xdfff)) fail();
          } else if (unit >= 0xdc00 && unit <= 0xdfff) fail();
        }
        return decoded;
      }
      if (character === '\\') offset++;
    }
    fail();
  }
  function value(depth) {
    if (depth > maxDepth) fail();
    whitespace();
    const character = source[offset];
    if (character === '{') {
      offset++; whitespace();
      const keys = new Set();
      if (source[offset] === '}') { offset++; return; }
      while (offset < source.length) {
        whitespace();
        const key = string();
        if (keys.has(key)) fail();
        keys.add(key); whitespace();
        if (source[offset++] !== ':') fail();
        value(depth + 1); whitespace();
        const next = source[offset++];
        if (next === '}') return;
        if (next !== ',') fail();
      }
      fail();
    } else if (character === '[') {
      offset++; whitespace();
      if (source[offset] === ']') { offset++; return; }
      while (offset < source.length) {
        value(depth + 1); whitespace();
        const next = source[offset++];
        if (next === ']') return;
        if (next !== ',') fail();
      }
      fail();
    } else if (character === '"') string();
    else {
      const start = offset;
      while (offset < source.length && !/[\s,\]}]/.test(source[offset])) offset++;
      if (offset === start) fail();
    }
  }
  value(0); whitespace();
  if (offset !== source.length) fail();
  return JSON.parse(source);
}

export function decodeBase64Url(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value)) throw new SyntaxError('Invalid base64url');
  const bytes = Buffer.from(value, 'base64url');
  if (bytes.toString('base64url') !== value) throw new SyntaxError('Noncanonical base64url');
  return bytes;
}
