/*
 * Wire
 * Copyright (C) 2019 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses/.
 *
 */

import {LogFactory, LoggerOptions} from '@wireapp/commons';
import {app, ipcMain} from 'electron';
import * as logdown from 'logdown';
import * as path from 'path';

import {EVENT_TYPE} from '../lib/eventType';
import {config} from '../settings/config';

const logDir = path.join(app.getPath('userData'), 'logs');
const logFile = path.join(logDir, 'electron.log');

const isDevelopment = config.environment !== 'production';
const forceLogging = process.argv.includes('--enable-logging');

export const LOGGER_NAMESPACE = '@wireapp/desktop';
export const ENABLE_LOGGING = isDevelopment || forceLogging;

export function getLogger(name: string): logdown.Logger {
  const options: LoggerOptions = {
    logFilePath: logFile,
    namespace: LOGGER_NAMESPACE,
    separator: '/',
  };

  if (ENABLE_LOGGING) {
    options.forceEnable = true;
  }

  return LogFactory.getLogger(name, options);
}

ipcMain.handle(EVENT_TYPE.IPC.LOG, (_event, type: 'error' | 'info' | 'log' | 'warn', name: string, logs: string[]) => {
  getLogger(name)[type](logs);
});
