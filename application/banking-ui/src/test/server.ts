import { setupServer } from "msw/node";
import { handlers } from "../mocks/handlers";

/** Same MSW handlers the dev server uses, so tests exercise the real mock contract. */
export const server = setupServer(...handlers);
