/*
 * SonarOmnisharp
 * Copyright (C) 2021-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

using System.Runtime.CompilerServices;

#if SignAssembly
[assembly: InternalsVisibleTo("SonarLint.OmniSharp.Plugin.UnitTests,PublicKey00240000048000009400000006020000002400005253413100040000010001002d1203012948a96517482ba6a1f5d349f2a9d59cf20a801c225c9ea11de74b4038bb46d843b77e7c4137c8cc4eb2d96db6c2e2bde16dd1c031bbe5ce79fe6018e744e4b1a098e275fa9662dd8d4ff73089f7583b186acf95c45e013c33b7277c5b8b64138210dccc6b6af05d92d4d6a65048fed73de6f4500905bcbcaf8d32c6")]
[assembly: InternalsVisibleTo("DynamicProxyGenAssembly2, PublicKey=0024000004800000940000000602000000240000525341310004000001000100c547cac37abd99c8db225ef2f6c8a3602f3b3606cc9891605d02baa56104f4cfc0734aa39b93bf7852f7d9266654753cc297e7d2edfe0bac1cdcf9f717241550e0a7b191195b7667bb4f64bcb8e2121380fd1d9d46ad2d92d2d15605093924cceaf74c4861eff62abf69b9291ed0a340e113be11e6a7d3113e92484cf7045cc7")]

#else

[assembly: InternalsVisibleTo("SonarLint.OmniSharp.Plugin.UnitTests")]
[assembly: InternalsVisibleTo("DynamicProxyGenAssembly2")]

#endif

