import assert from "node:assert/strict";
import test from "node:test";

import {
  bootstrapTokenFromUrl,
  sanitizedBootstrapLocation,
} from "../src/bootstrapToken.ts";

test("reads a token from a URL fragment", () => {
  const url = new URL("http://100.80.12.34:8899/webchat/#token=a%2Bb%2Fc%3D%3D");
  assert.equal(bootstrapTokenFromUrl(url), "a+b/c==");
});

test("keeps query-token compatibility but prefers the fragment", () => {
  const url = new URL("http://localhost/webchat/?token=legacy#token=current");
  assert.equal(bootstrapTokenFromUrl(url), "current");
});

test("removes bootstrap credentials while retaining unrelated URL state", () => {
  const url = new URL(
    "http://localhost/webchat/?theme=dark&token=legacy#token=current&pane=workspace",
  );
  assert.equal(
    sanitizedBootstrapLocation(url),
    "/webchat/?theme=dark#pane=workspace",
  );
});
