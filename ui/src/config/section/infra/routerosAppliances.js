// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import { shallowRef, defineAsyncComponent } from 'vue'
import store from '@/store'

// Mikrotik RouterOS CHR appliances are domain_router rows with role ROUTEROS_VM.
// They are managed through their guest network / VPC lifecycle rather than the
// systemvm virtual-router APIs, so this Infrastructure view is a read-only listing.
export default {
  name: 'routerosappliance',
  title: 'label.mikrotik.chr',
  icon: 'apartment-outlined',
  permission: ['listRouters'],
  params: { projectid: '-1', role: 'ROUTEROS_VM' },
  columns: ['name', 'state', 'publicip', { field: 'guestnetworkname', customTitle: 'network' }, 'vpcname', 'hostname', 'account', 'zonename'],
  searchFilters: ['name', 'zoneid', 'podid', 'clusterid'],
  details: ['name', 'id', 'state', 'publicip', 'guestnetworkname', 'vpcname', 'guestipaddress', 'linklocalip', 'serviceofferingname', 'hostname', 'account', 'zonename', 'created', 'hostcontrolstate'],
  resourceType: 'VirtualRouter',
  filters: () => {
    return ['starting', 'running', 'stopping', 'stopped', 'destroyed', 'expunging', 'migrating', 'error', 'unknown', 'shutdown']
  },
  tabs: [{
    name: 'details',
    component: shallowRef(defineAsyncComponent(() => import('@/components/view/DetailsTab.vue')))
  }, {
    name: 'nics',
    component: shallowRef(defineAsyncComponent(() => import('@/views/network/NicsTable.vue')))
  }, {
    name: 'events',
    resourceType: 'DomainRouter',
    component: shallowRef(defineAsyncComponent(() => import('@/components/view/EventsTab.vue'))),
    show: () => { return 'listEvents' in store.getters.apis }
  }, {
    name: 'comments',
    component: shallowRef(defineAsyncComponent(() => import('@/components/view/AnnotationsTab.vue')))
  }],
  related: [{
    name: 'vm',
    title: 'label.instances',
    param: 'networkid',
    value: 'guestnetworkid'
  }]
}
