"""Per-request context propagation.

The HTTP middleware stores the request id (from the ``X-Request-ID`` header or a
freshly generated value) into a :class:`contextvars.ContextVar`. Services such as
:func:`app.services.audit.append_audit` read the current request id from this
context var so every audit event is correlated with the HTTP request that caused
it — even when the caller does not pass ``request_id`` explicitly. This keeps the
``X-Request-ID`` response header and the audit ``request_id`` column identical.
"""
from __future__ import annotations

from contextvars import ContextVar

#: Current request id (``X-Request-ID`` header or ``req_<hex>`` fallback).
current_request_id: ContextVar[str | None] = ContextVar("current_request_id", default=None)


def get_current_request_id() -> str | None:
    """Return the request id bound to the current async context, or None."""
    return current_request_id.get()
