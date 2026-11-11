"""Pydantic data models for OpenAI-compatible chat completions and Prism admin endpoints.
"""

from __future__ import annotations

import time
import uuid
from typing import Any, Dict, List, Optional, Union
from pydantic import BaseModel, Field


class ChatMessage(BaseModel):
    role: str
    content: Union[str, List[Any]]


class ChatCompletionRequest(BaseModel):
    model: str
    messages: List[ChatMessage]
    stream: Optional[bool] = False
    temperature: Optional[float] = 1.0
    max_tokens: Optional[int] = None
    stop: Optional[Union[str, List[str]]] = None


class UsageInfo(BaseModel):
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0


class ChatChoiceMessage(BaseModel):
    role: str = "assistant"
    content: str


class ChatChoice(BaseModel):
    index: int = 0
    message: ChatChoiceMessage
    finish_reason: Optional[str] = "stop"


class ChatCompletionResponse(BaseModel):
    id: str = Field(default_factory=lambda: f"chatcmpl-{uuid.uuid4().hex[:12]}")
    object: str = "chat.completion"
    created: int = Field(default_factory=lambda: int(time.time()))
    model: str
    choices: List[ChatChoice]
    usage: UsageInfo


class ErrorDetail(BaseModel):
    message: str
    type: str
    code: Optional[str] = None
    param: Optional[str] = None


class ErrorResponse(BaseModel):
    error: ErrorDetail


class UsageSummaryResponse(BaseModel):
    key: str
    from_date: Optional[str] = Field(None, alias="from")
    to_date: Optional[str] = Field(None, alias="to")
    requests: int
    prompt_tokens: int
    completion_tokens: int
    cost_usd: float
    cache_hits: int

    class Config:
        populate_by_name = True


class RequestLogRecord(BaseModel):
    request_id: str
    virtual_key: str
    requested_model: str
    resolved_provider: Optional[str] = None
    resolved_model: Optional[str] = None
    status: str
    prompt_tokens: int = 0
    completion_tokens: int = 0
    cost_usd: float = 0.0
    cache: str = "miss"
    fallback: bool = False
    route_reason: Optional[str] = None
    retries: int = 0
    latency_ms: int = 0
    created_at: str
