import { useEffect, useState } from "react";
import { api } from "./api";

/** Abort replaced reads and discard stale results; a retry always reloads authoritative server data. */
export function useResource<T>(path: string) {
  const [data, setData] = useState<T>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    setData(undefined);
    api<T>(path, { signal: controller.signal })
      .then((value) => {
        if (!controller.signal.aborted) setData(value);
      })
      .catch((error) => {
        if (!controller.signal.aborted) setError(error.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [path, revision]);
  return {
    data,
    loading,
    error,
    reload: () => setRevision((value) => value + 1),
  };
}
