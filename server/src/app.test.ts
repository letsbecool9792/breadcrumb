import assert from "node:assert/strict";
import { once } from "node:events";
import type { Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, test } from "node:test";
import { createApp } from "./app.ts";

let server: Server;
let base: string;

before(async () => {
  // port 0: whatever is free, so a running dev server never collides with the tests
  const unused = async () => {
    throw new Error("not used by these tests");
  };
  server = createApp({ log: false, extract: unused, embed: unused }).listen(0, "127.0.0.1");
  await once(server, "listening");
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

after(() => {
  server.closeAllConnections();
  server.close();
});

test("GET /health names the service and says it is up", async () => {
  const res = await fetch(`${base}/health`);

  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type") ?? "", /^application\/json/);
  assert.deepEqual(await res.json(), { service: "breadcrumb", status: "ok" });
});

test("an unknown path is a JSON 404, not an HTML page", async () => {
  const res = await fetch(`${base}/nope`);

  assert.equal(res.status, 404);
  assert.deepEqual(await res.json(), { error: "not found" });
});

test("responses do not advertise Express", async () => {
  const res = await fetch(`${base}/health`);

  assert.equal(res.headers.get("x-powered-by"), null);
});
