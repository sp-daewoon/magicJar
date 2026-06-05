#!/usr/bin/env node

import http from 'node:http'
import { execFile } from 'node:child_process'

const host = process.env.CLAUDE_DISCOVERY_BRIDGE_HOST ?? '127.0.0.1'
const port = Number(process.env.CLAUDE_DISCOVERY_BRIDGE_PORT ?? '8787')
const command = process.env.CLAUDE_DISCOVERY_COMMAND ?? 'claude'
const token = process.env.CLAUDE_DISCOVERY_BRIDGE_TOKEN ?? ''
const maxBodyBytes = 64 * 1024

function json(res, status, payload) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(payload))
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > maxBodyBytes) {
        reject(new Error('request body too large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function runClaude(prompt, timeoutSeconds) {
  return new Promise((resolve, reject) => {
    execFile(
      command,
      ['-p', prompt],
      {
        timeout: Math.max(1, timeoutSeconds) * 1000,
        maxBuffer: 256 * 1024,
        windowsHide: true,
      },
      (error, stdout, stderr) => {
        if (error) {
          reject(new Error(`${error.message}${stderr ? `\n${stderr}` : ''}`))
          return
        }
        resolve(stdout)
      },
    )
  })
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    json(res, 200, { status: 'UP', command })
    return
  }
  if (req.method !== 'POST' || req.url !== '/discover') {
    json(res, 404, { error: 'not found' })
    return
  }
  if (token && req.headers['x-claude-discovery-token'] !== token) {
    json(res, 401, { error: 'unauthorized' })
    return
  }

  try {
    const body = JSON.parse(await readBody(req))
    const prompt = typeof body.prompt === 'string' ? body.prompt : ''
    const timeoutSeconds = Number(body.timeoutSeconds ?? 45)
    if (!prompt.trim()) {
      json(res, 400, { error: 'prompt is required' })
      return
    }
    const output = await runClaude(prompt, timeoutSeconds)
    json(res, 200, { output })
  } catch (error) {
    json(res, 503, { error: error instanceof Error ? error.message : String(error) })
  }
})

server.listen(port, host, () => {
  console.log(`claude discovery bridge listening on http://${host}:${port}`)
})
