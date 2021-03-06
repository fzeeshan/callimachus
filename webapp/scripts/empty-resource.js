// empty-resource.js
/*
 * Copyright (c) 2014 3 Round Stones Inc., Some Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

(function($){

var calli = window.calli || (window.calli={});

calli.isEmptyResource = function(element) {
    var selector = "[about],[src],[typeof],[typeof=''],[resource],[href],[property]";
    var el = $(element && typeof element == 'object' ? element : this);
    return !el.is(selector) && el.find(selector).length === 0;
};

})(window.jQuery);

