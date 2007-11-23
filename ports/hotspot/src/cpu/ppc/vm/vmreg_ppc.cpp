/*
 * Copyright 2006-2007 Sun Microsystems, Inc.  All Rights Reserved.
 * Copyright 2007 Red Hat, Inc.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *
 */

#include "incls/_precompiled.incl"
#include "incls/_vmreg_ppc.cpp.incl"

void VMRegImpl::set_regName()
{
  int i = 0;
  Register reg = ::as_Register(0);
  for ( ; i < ConcreteRegisterImpl::max_gpr ; ) {
    regName[i++] = reg->name();
    reg = reg->successor();
  }
  FloatRegister freg = ::as_FloatRegister(0);
  for ( ; i < ConcreteRegisterImpl::max_fpr ; ) {
    regName[i++] = freg->name();
    freg = freg->successor();
  }
  for ( ; i < ConcreteRegisterImpl::number_of_registers; i++) {
    Unimplemented();
  }
}

Register VMRegImpl::as_Register()
{
  Unimplemented();
}
