/*
 * Copyright (c) 2011 Kurt Aaholst <kaaholst@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.danga.squeezer.itemlists;
import com.danga.squeezer.model.SqueezerAlbum;
import com.danga.squeezer.service.SqueezerServerState;

oneway interface IServiceAlbumListCallback {
  void onAlbumsReceived(int count, int start, in List<SqueezerAlbum> albums);
  void onServerStateChanged(in SqueezerServerState oldState, in SqueezerServerState newState);
}

