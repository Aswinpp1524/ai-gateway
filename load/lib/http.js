import http from 'k6/http';

import { BASE_URL } from './config.js';

/**
 * Every scenario in this directory sends chat completion requests through this one function, so
 * the auth header - the one thing every scenario requires - lives in exactly one place.
 */
export function postChatCompletion(payload, apiKey, tags) {
    return http.post(`${BASE_URL}/v1/chat/completions`, JSON.stringify(payload), {
        headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${apiKey}`,
        },
        tags,
    });
}
