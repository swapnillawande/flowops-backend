import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import {
  X,
  PackageOpen,
  ArrowLeft,
  ArrowRight,
  LoaderCircle,
} from "lucide-react";
import { api } from "./api";
import type { HistoryEvent, Status } from "./types";

export const label = (value: string) =>
  value.toLowerCase().replaceAll("_", " ");
export const short = (value: string) => value.slice(0, 8).toUpperCase();
export const date = (value: string) =>
  new Date(value).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
export function Badge({ status }: { status: Status }) {
  return (
    <span className={`badge ${status.toLowerCase()}`}>
      <i />
      {label(status)}
    </span>
  );
}
export function Empty({
  title,
  children,
}: {
  title: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <PackageOpen size={30} />
      <h3>{title}</h3>
      <p>{children}</p>
    </div>
  );
}
export function Pager({
  page,
  pages,
  total,
  onChange,
}: {
  page: number;
  pages: number;
  total: number;
  onChange: (page: number) => void;
}) {
  return (
    <div className="pager">
      <span>
        {total} records · Page {page + 1} of {Math.max(1, pages)}
      </span>
      <div>
        <button
          aria-label="Previous page"
          disabled={page === 0}
          onClick={() => onChange(page - 1)}
        >
          <ArrowLeft size={15} />
        </button>
        <button
          aria-label="Next page"
          disabled={page + 1 >= pages}
          onClick={() => onChange(page + 1)}
        >
          <ArrowRight size={15} />
        </button>
      </div>
    </div>
  );
}
/** Native dialog provides focus containment, Escape handling, and focus restoration without a UI framework. */
export function Modal({
  title,
  children,
  close,
}: {
  title: string;
  children: ReactNode;
  close: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current!;
    dialog.showModal();
    return () => dialog.close();
  }, []);
  return (
    <dialog
      ref={ref}
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      aria-labelledby="dialog-title"
    >
      <div className="modal-title">
        <h2 id="dialog-title">{title}</h2>
        <button aria-label="Close dialog" onClick={close}>
          <X size={19} />
        </button>
      </div>
      {children}
    </dialog>
  );
}
/** Keep the retry key while this form remains open, changing it only if the submitted payload changes. */
export function useRetryKey() {
  const ref = useRef({ payload: "", key: "" });
  return (payload: unknown) => {
    const serialized = JSON.stringify(payload);
    if (serialized !== ref.current.payload)
      ref.current = { payload: serialized, key: crypto.randomUUID() };
    return ref.current.key;
  };
}
/** Errors stay beside the form; closing is left to the caller after a confirmed success. */
export function MutationForm({
  submit,
  children,
  button = "Save",
  busyLabel = "Saving…",
}: {
  submit: () => Promise<void>;
  children: ReactNode;
  button?: string;
  busyLabel?: string;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  async function handle(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await submit();
    } catch (error) {
      setError((error as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <form onSubmit={handle}>
      <fieldset disabled={busy}>{children}</fieldset>
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <button className="primary submit" disabled={busy}>
        {busy && <LoaderCircle size={16} className="spin" />}
        {busy ? busyLabel : button}
      </button>
    </form>
  );
}
export function History({ id }: { id: string }) {
  const [events, setEvents] = useState<HistoryEvent[]>([]),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    api<HistoryEvent[]>(`/shipments/${id}/history`, {
      signal: controller.signal,
    })
      .then(setEvents)
      .catch((error) => {
        if (error.name !== "AbortError") setError(error.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [id]);
  if (loading) return <p role="status">Loading shipment history…</p>;
  if (error)
    return (
      <p role="alert" className="form-error">
        {error}
      </p>
    );
  return (
    <ol className="timeline">
      {events.map((event) => (
        <li key={event.id}>
          <span className="timeline-dot" />
          <div>
            <Badge status={event.status} />
            <p>{event.note}</p>
            <small>{date(event.createdAt)}</small>
          </div>
        </li>
      ))}
    </ol>
  );
}
